package com.begics.rag.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels.SourceReference;
import com.begics.rag.domain.RetrievedChunk;
import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.service.StorageQuotaService;
import com.begics.rag.service.WorkspaceAccessService;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import com.begics.rag.storage.S3ObjectWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.security.authentication.TestingAuthenticationToken;
import java.io.IOException;
import java.io.InputStream;

@Testcontainers(disabledWithoutDocker = true)
class JdbcDocumentStoreIntegrationTest {
  private static final int INITIAL_VECTOR_DIMENSIONS = 768;
  private static final int VECTOR_DIMENSIONS = 3_584;

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("pgvector/pgvector:pg17")
          .withDatabaseName("rag")
          .withUsername("rag")
          .withPassword("rag");

  private DriverManagerDataSource dataSource;
  private JdbcDocumentStore store;

  @BeforeEach
  void migrateAndCreateStore() {
    dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .placeholders(Map.of(
            "embeddingDimensions", "1536",
            "targetEmbeddingDimensions", Integer.toString(VECTOR_DIMENSIONS)))
        .load()
        .migrate();
    store = new JdbcDocumentStore(jdbc);
  }

  @Test
  void persistsAndRanksChunksWithTheirSourceReferences() {
    String firstId = store.save("notes.txt", List.of("aligned", "orthogonal"),
        List.of(vector(1, 0), vector(0, 1)), "test-model", null);
    String secondId = store.save("other.txt", List.of("nearby"), List.of(vector(0.8f, 0.6f)), "test-model", null);

    List<RetrievedChunk> results = store.search("no lexical match", vector(1, 0), "test-model", 3);

    assertThat(results).extracting(RetrievedChunk::content)
        .containsExactly("aligned", "nearby", "orthogonal");
    assertThat(results.stream()
        .map(chunk -> new SourceReference(chunk.documentId(), chunk.source(), chunk.chunk())).toList())
        .containsExactly(
            new SourceReference(firstId, "notes.txt", 0),
            new SourceReference(secondId, "other.txt", 0),
            new SourceReference(firstId, "notes.txt", 1));
  }

  @Test
  void isolatesRetrievalAndReembeddingBetweenWorkspaces() {
    UUID firstWorkspace = UUID.randomUUID();
    UUID secondWorkspace = UUID.randomUUID();
    JdbcTemplate jdbc = jdbc();
    jdbc.update("insert into workspaces(id, name) values (?, ?)", firstWorkspace, "First");
    jdbc.update("insert into workspaces(id, name) values (?, ?)", secondWorkspace, "Second");
    store.save(firstWorkspace, "private.txt", List.of("private workspace token"),
        List.of(vector(1, 0)), "test-model", null);
    String secondDocumentId = store.save(secondWorkspace, "other.txt", List.of("other workspace token"),
        List.of(vector(0, 1)), "old-model", null);

    assertThat(store.search(firstWorkspace, "other workspace token", vector(0, 1), "test-model", 5))
        .extracting(RetrievedChunk::source)
        .doesNotContain("other.txt");
    List<DocumentStore.ChunkToEmbed> stale = store.findChunksWithStaleEmbeddings(firstWorkspace, "new-model", 10);
    assertThat(stale).extracting(DocumentStore.ChunkToEmbed::content)
        .containsExactly("private workspace token");
    store.updateEmbedding(firstWorkspace, stale.getFirst().id(), vector(1, 0), "new-model");
    assertThat(jdbc.queryForObject("""
        select embedding_model from chunks c join documents d on d.id = c.document_id
         where d.id = ?::uuid
        """, String.class, secondDocumentId)).isEqualTo("old-model");
  }

  @Test
  void treatsSqlInjectionShapedSearchFiltersAsValuesAndPreservesWorkspaceIsolation() {
    UUID firstWorkspace = UUID.randomUUID();
    UUID secondWorkspace = UUID.randomUUID();
    JdbcTemplate jdbc = jdbc();
    jdbc.update("insert into workspaces(id, name) values (?, ?)", firstWorkspace, "First");
    jdbc.update("insert into workspaces(id, name) values (?, ?)", secondWorkspace, "Second");
    store.save(firstWorkspace, "private.txt", List.of("first workspace document"),
        List.of(vector(1, 0)), "test-model", null);
    store.save(secondWorkspace, "other.txt", List.of("second workspace document"),
        List.of(vector(0, 1)), "test-model", null);

    List<RetrievedChunk> sourceInjection = store.search(
        firstWorkspace, "document", vector(1, 0), "test-model", 5,
        new DocumentStore.DocumentFilter("missing.txt' OR 1=1 --", null));
    List<RetrievedChunk> typeInjection = store.search(
        firstWorkspace, "document", vector(1, 0), "test-model", 5,
        new DocumentStore.DocumentFilter(null, "txt' OR 1=1 --"));

    assertThat(sourceInjection).isEmpty();
    assertThat(typeInjection).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from documents", Integer.class)).isEqualTo(2);
    assertThat(store.search(firstWorkspace, "document", vector(1, 0), "test-model", 5))
        .extracting(RetrievedChunk::source)
        .containsExactly("private.txt");
  }

  @Test
  void listsIndexedDocumentsAndChunkCountsOnlyForTheirWorkspace() {
    UUID firstWorkspace = UUID.randomUUID();
    UUID secondWorkspace = UUID.randomUUID();
    JdbcTemplate jdbc = jdbc();
    jdbc.update("insert into workspaces(id, name) values (?, ?)", firstWorkspace, "First");
    jdbc.update("insert into workspaces(id, name) values (?, ?)", secondWorkspace, "Second");
    store.save(firstWorkspace, "uploads/first/notes.txt",
        List.of("first chunk", "second chunk"), List.of(vector(1, 0), vector(0, 1)), "test-model", null);
    store.save(secondWorkspace, "uploads/second/private.pdf",
        List.of("private chunk"), List.of(vector(1, 0)), "test-model", null);

    assertThat(store.listDocuments(firstWorkspace))
        .extracting(DocumentStore.IndexedDocument::source, DocumentStore.IndexedDocument::chunks)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("uploads/first/notes.txt", 2));
  }

  @Test
  void resolvesDocumentSourceOnlyInsideItsWorkspace() {
    UUID firstWorkspace = UUID.randomUUID();
    UUID secondWorkspace = UUID.randomUUID();
    jdbc().update("insert into workspaces(id, name) values (?, ?)", firstWorkspace, "First");
    jdbc().update("insert into workspaces(id, name) values (?, ?)", secondWorkspace, "Second");
    String id = store.save(firstWorkspace, "uploads/" + firstWorkspace + "/notes.txt",
        List.of("content"), List.of(vector(1, 0)), "test-model", null);

    assertThat(store.sourceChunk(firstWorkspace, UUID.fromString(id), 0))
        .contains(new DocumentStore.SourceChunk("uploads/" + firstWorkspace + "/notes.txt", "content"));
    assertThat(store.sourceChunk(secondWorkspace, UUID.fromString(id), 0)).isEmpty();
  }

  @Test
  void persistsIndexingJobStagesAndRecentWorkspaceStatus() {
    UUID workspaceId = UUID.randomUUID();
    jdbc().update("insert into workspaces(id, name) values (?, ?)", workspaceId, "Jobs");
    IndexingJobStore jobs = new IndexingJobStore(jdbc());
    String key = "uploads/" + workspaceId + "/file/notes.txt";
    new UploadedObjectStore(jdbc()).record(key, workspaceId, "uploader");
    IndexingJob created = jobs.create(workspaceId, key, key, null, "submitter");

    assertThat(created.stage()).isEqualTo(IndexingJobStore.Stage.QUEUED);
    assertThat(created.uploadedBy()).isEqualTo("uploader");
    assertThat(created.uploadedAt()).isNotNull();
    assertThat(created.submittedBy()).isEqualTo("submitter");
    assertThat(jobs.markProcessing(created.id())).isTrue();
    jobs.saveScreeningText(created.id(), "screened text");
    jobs.updateStage(created.id(), IndexingJobStore.Stage.SCREENING);
    jobs.awaitApproval(created.id(), "screened text", "[]");
    var pending = jobs.get(created.id(), workspaceId).orElseThrow();
    assertThat(pending.status()).isEqualTo("AWAITING_APPROVAL");
    assertThat(pending.screenedAt()).isNotNull();
    assertThat(jobs.hasActiveObjectKey(workspaceId, key)).isTrue();
    assertThatThrownBy(() -> jobs.complete(created.id(), "document-id", 1))
        .isInstanceOf(IllegalStateException.class);
    assertThat(jobs.approve(created.id(), workspaceId, "reviewer")).isTrue();
    assertThat(jobs.markProcessing(created.id())).isTrue();
    jobs.updateStage(created.id(), IndexingJobStore.Stage.CHUNKING);
    assertThat(jobs.get(created.id(), workspaceId).orElseThrow().stage())
        .isEqualTo(IndexingJobStore.Stage.CHUNKING);
    jobs.complete(created.id(), "document-id", 3);

    assertThat(jobs.recent(workspaceId))
        .singleElement()
        .satisfies(job -> {
          assertThat(job.status()).isEqualTo("COMPLETED");
          assertThat(job.stage()).isEqualTo(IndexingJobStore.Stage.COMPLETED);
          assertThat(job.chunks()).isEqualTo(3);
          assertThat(job.reviewedBy()).isEqualTo("reviewer");
          assertThat(job.reviewedAt()).isNotNull();
          assertThat(job.indexedDocumentId()).isEqualTo("document-id");
          assertThat(job.indexedAt()).isNotNull();
        });
  }

  @Test
  void migratesExistingChunksWithoutRetainingLegacyVectors() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("2"))
        .placeholders(Map.of("embeddingDimensions", "768", "targetEmbeddingDimensions", "768"))
        .load()
        .migrate();
    String documentId = "8f04f78a-bc4b-4ac4-86b4-7e314d0b1bd1";
    jdbc.update("insert into documents(id, source) values (?::uuid, ?)", documentId, "preserved.txt");
    jdbc.update("insert into chunks(document_id, chunk_no, content, embedding) "
        + "values (?::uuid, 0, ?, ?::vector)", documentId, "existing searchable content",
        legacyVector(1, 0));
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .placeholders(Map.of("embeddingDimensions", "768", "targetEmbeddingDimensions", "768"))
        .load()
        .migrate();
    store = new JdbcDocumentStore(jdbc);

    Integer indexedDimensions = jdbc.queryForObject(
        "select vector_dims(embedding::vector) from chunks where document_id = ?::uuid",
        Integer.class, documentId);
    Boolean legacyColumnExists = jdbc.queryForObject(
        "select exists (select 1 from information_schema.columns "
            + "where table_schema = 'public' and table_name = 'chunks' "
            + "and column_name = 'embedding_legacy')",
        Boolean.class);
    String currentType = jdbc.queryForObject(
        "select format_type(a.atttypid, a.atttypmod) from pg_attribute a "
            + "join pg_class c on c.oid = a.attrelid "
            + "where c.relname = 'chunks' and a.attname = 'embedding'",
        String.class);
    List<RetrievedChunk> results = store.search("existing searchable content", vector(1, 0), "test-model", 5);

    assertThat(legacyColumnExists).isFalse();
    assertThat(indexedDimensions).isNull();
    assertThat(currentType).isEqualTo("halfvec(768)");
    assertThat(results).extracting(RetrievedChunk::content)
        .containsExactly("existing searchable content");
  }

  @Test
  void combinesVectorAndPostgresFullTextResultsWithReciprocalRankFusion() {
    String lexicalId = store.save("manual.txt", List.of("zebralight calibration procedure"),
        List.of(vector(0, 1)), "test-model", null);
    String vectorId = store.save("misc.txt", List.of("unrelated reference material"),
        List.of(vector(1, 0)), "test-model", null);

    List<RetrievedChunk> results = store.search("zebralight", vector(1, 0), "test-model", 2);

    assertThat(results).extracting(RetrievedChunk::content)
        .containsExactly("zebralight calibration procedure", "unrelated reference material");
    assertThat(results.getFirst().documentId()).isEqualTo(lexicalId);
    assertThat(results.get(1).documentId()).isEqualTo(vectorId);
  }

  @Test
  void filtersRetrievalByDocumentTypeAndSource() {
    store.save("manual.pdf", List.of("pdf calibration procedure"),
        List.of(vector(1, 0)), "test-model", null);
    store.save("notes.txt", List.of("text calibration procedure"),
        List.of(vector(1, 0)), "test-model", null);

    List<RetrievedChunk> pdfResults = store.search(
        JdbcDocumentStore.LEGACY_WORKSPACE_ID, "calibration",
        vector(1, 0), "test-model", 5,
        new DocumentStore.DocumentFilter(null, "pdf"));

    List<RetrievedChunk> sourceResults = store.search(
        JdbcDocumentStore.LEGACY_WORKSPACE_ID, "calibration",
        vector(1, 0), "test-model", 5,
        new DocumentStore.DocumentFilter("manual.pdf", null));

    assertThat(pdfResults).extracting(RetrievedChunk::source).containsExactly("manual.pdf");
    assertThat(sourceResults).extracting(RetrievedChunk::source).containsExactly("manual.pdf");
  }

  @Test
  void selectsAndUpdatesChunksWithMissingEmbeddings() {
    String documentId = store.save("manual.txt", List.of("needs embedding"), List.of(vector(0, 1)), "old-model", null);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<DocumentStore.ChunkToEmbed> pending = store.findChunksWithStaleEmbeddings("new-model", 100);

    assertThat(pending).hasSize(1);
    assertThat(pending.getFirst().content()).isEqualTo("needs embedding");
    assertThat(store.search("no lexical match", vector(1, 0), "new-model", 1)).isEmpty();
    assertThat(new JdbcTemplate(dataSource).queryForObject(
        "select embedding_model from chunks where document_id = ?::uuid", String.class, documentId))
        .isEqualTo("old-model");
    store.updateEmbedding(pending.getFirst().id(), vector(1, 0), "new-model");

    assertThat(store.findChunksWithStaleEmbeddings("new-model", 100)).isEmpty();
    assertThat(store.search("no lexical match", vector(1, 0), "new-model", 1))
        .extracting(RetrievedChunk::content)
        .containsExactly("needs embedding");
  }

  @Test
  void indexingJobDocumentIdMakesRetriesIdempotent() {
    UUID documentId = UUID.randomUUID();
    store.save("job-source.txt", List.of("first attempt", "stale extra chunk"),
        List.of(vector(1, 0), vector(0, 1)), "model-v1", documentId);
    String retriedId = store.save("job-source.txt", List.of("successful retry"),
        List.of(vector(1, 0)), "model-v1", documentId);

    assertThat(retriedId).isEqualTo(documentId.toString());
    assertThat(new JdbcTemplate(dataSource).queryForObject(
        "select count(*) from chunks where document_id = ?::uuid", Integer.class, documentId.toString()))
        .isEqualTo(1);
    assertThat(store.search("successful retry", vector(1, 0), "model-v1", 5))
        .extracting(RetrievedChunk::content)
        .containsExactly("successful retry");
  }

  @Test
  void meetsTheRetrievalEvaluationDatasetRecallAndReciprocalRankThresholds() throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    List<RetrievalCase> cases;
    try (InputStream input = getClass().getResourceAsStream("/retrieval-evaluation.json")) {
      if (input == null) {
        throw new IllegalStateException("Retrieval evaluation dataset is missing");
      }
      cases = objectMapper.readValue(input, new TypeReference<>() {});
    }

    int hits = 0;
    double reciprocalRankSum = 0;
    for (RetrievalCase evaluation : cases) {
      for (EvaluationDocument document : evaluation.documents()) {
        store.save(document.source(), List.of(document.text()),
            List.of(vector((float) document.vector()[0], (float) document.vector()[1])),
            "evaluation-model", null);
      }
      List<RetrievedChunk> results = store.search(
          evaluation.question(),
          vector((float) evaluation.queryVector()[0], (float) evaluation.queryVector()[1]),
          "evaluation-model", 5);
      int rank = -1;
      for (int i = 0; i < results.size(); i++) {
        if (results.get(i).source().equals(evaluation.expectedSource())) {
          rank = i + 1;
          break;
        }
      }
      if (rank > 0) {
        hits++;
        reciprocalRankSum += 1.0 / rank;
      }
      jdbc().execute("truncate table documents cascade");
    }

    double recallAtFive = (double) hits / cases.size();
    double meanReciprocalRank = reciprocalRankSum / cases.size();
    assertThat(recallAtFive).isGreaterThanOrEqualTo(1.0);
    assertThat(meanReciprocalRank).isGreaterThanOrEqualTo(0.75);
  }

  @Test
  void indexingJobStorePersistsStatusAndAllowsRecovery() {
    IndexingJobStore jobs = new IndexingJobStore(jdbc());
    IndexingJobStore.IndexingJob created = jobs.create(
        JdbcDocumentStore.LEGACY_WORKSPACE_ID, "job-source.txt", null, "content for indexing");
    IndexingJobStore.IndexingJob transcription = jobs.create(
        JdbcDocumentStore.LEGACY_WORKSPACE_ID, "uploads/audio.mp3", "uploads/audio.mp3", null);
    IndexingJobStore.IndexingJob reembed = jobs.createReembed(JdbcDocumentStore.LEGACY_WORKSPACE_ID);

    assertThat(created.status()).isEqualTo("QUEUED");
    assertThat(jobs.get(created.id(), UUID.randomUUID())).isEmpty();
    assertThat(jobs.get(created.id(), JdbcDocumentStore.LEGACY_WORKSPACE_ID)).isPresent();
    assertThat(reembed.jobType()).isEqualTo("REEMBED");
    assertThat(reembed.objectKey()).isNull();
    assertThat(reembed.content()).isNull();
    assertThat(jobs.markProcessing(transcription.id())).isTrue();
    jobs.updateStage(transcription.id(), IndexingJobStore.Stage.TRANSCRIBING);
    assertThat(jobs.markProcessing(created.id())).isTrue();
    jobs.recoverProcessingJobs();
    assertThat(jobs.get(created.id()).orElseThrow().status()).isEqualTo("QUEUED");
    var failedTranscription = jobs.get(transcription.id()).orElseThrow();
    assertThat(failedTranscription.status()).isEqualTo("FAILED");
    assertThat(failedTranscription.stage()).isEqualTo(IndexingJobStore.Stage.TRANSCRIBING);
    assertThat(failedTranscription.errorMessage()).contains("Re-index the file to retry");
    assertThat(failedTranscription.objectKey()).isNull();
    assertThat(jobs.markProcessing(created.id())).isTrue();
    jobs.updateStage(created.id(), IndexingJobStore.Stage.SCREENING);
    jobs.awaitApproval(created.id(), "content for indexing", "[]");
    assertThat(jobs.approve(created.id(), JdbcDocumentStore.LEGACY_WORKSPACE_ID, "reviewer")).isTrue();
    assertThat(jobs.markProcessing(created.id())).isTrue();
    jobs.complete(created.id(), "document-id", 3);
    assertThat(jobs.get(created.id()).orElseThrow().chunks()).isEqualTo(3);
    assertThat(jobs.get(created.id()).orElseThrow().status()).isEqualTo("COMPLETED");
    assertThat(jobs.get(created.id()).orElseThrow().content()).isNull();
    assertThat(jobs.markProcessing(reembed.id())).isTrue();
    jobs.complete(reembed.id(), 39);
    assertThat(jobs.get(reembed.id()).orElseThrow().status()).isEqualTo("COMPLETED");
    assertThat(jobs.get(reembed.id()).orElseThrow().chunks()).isEqualTo(39);

    IndexingJobStore.IndexingJob failed = jobs.create(
        JdbcDocumentStore.LEGACY_WORKSPACE_ID, "failed-source.txt", null, "content");
    assertThat(jobs.markProcessing(failed.id())).isTrue();
    jobs.updateStage(failed.id(), IndexingJobStore.Stage.CHUNKING);
    jobs.fail(failed.id(), "test failure");
    var failedJob = jobs.get(failed.id()).orElseThrow();
    assertThat(failedJob.status()).isEqualTo("FAILED");
    assertThat(failedJob.stage()).isEqualTo(IndexingJobStore.Stage.CHUNKING);
  }

  @Test
  void pagesQueuedJobsWithoutReturningAlreadyScheduledIds() {
    IndexingJobStore jobs = new IndexingJobStore(jdbc());
    List<IndexingJob> created = List.of(
        jobs.create(JdbcDocumentStore.LEGACY_WORKSPACE_ID, "page-a.txt", null, "content"),
        jobs.create(JdbcDocumentStore.LEGACY_WORKSPACE_ID, "page-b.txt", null, "content"),
        jobs.create(JdbcDocumentStore.LEGACY_WORKSPACE_ID, "page-c.txt", null, "content"));

    var firstPage = jobs.queuedPage(List.of(), 2);
    var secondPage = jobs.queuedPage(firstPage, 2);

    assertThat(firstPage).hasSize(2);
    assertThat(secondPage).hasSize(1);
    var allPageIds = java.util.stream.Stream.concat(firstPage.stream(), secondPage.stream()).toList();
    assertThat(allPageIds)
        .containsExactlyInAnyOrderElementsOf(created.stream().map(IndexingJob::id).toList());
    assertThat(jobs.queuedPage(allPageIds, 2)).isEmpty();
    assertThatThrownBy(() -> jobs.queuedPage(null, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> jobs.queuedPage(List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void provisionsPrivateWorkspacesAndEnforcesOwnerOnlyAccess() {
    WorkspaceAccessService access = new WorkspaceAccessService(jdbc());
    var alice = new TestingAuthenticationToken("alice-sub", null, "ROLE_USER");
    var bob = new TestingAuthenticationToken("bob-sub", null, "ROLE_USER");

    UUID aliceWorkspace = access.resolve(alice, null);
    UUID bobWorkspace = access.resolve(bob, null);
    assertThat(aliceWorkspace).isNotEqualTo(bobWorkspace);
    assertThatThrownBy(() -> access.resolve(bob, aliceWorkspace.toString()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("Workspace access denied");

    var aliceTeam = access.create(alice, "Team");
    assertThat(access.resolve(alice, aliceTeam.id().toString())).isEqualTo(aliceTeam.id());
    assertThatThrownBy(() -> access.resolve(bob, aliceTeam.id().toString()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void selectsTheLatestIndexingStatusPerSourceWithinItsWorkspace() {
    UUID firstWorkspace = UUID.randomUUID();
    UUID secondWorkspace = UUID.randomUUID();
    String source = "uploads/shared/manual.pdf";
    JdbcTemplate jdbc = jdbc();
    jdbc.update("insert into workspaces(id, name) values (?, ?)", firstWorkspace, "First");
    jdbc.update("insert into workspaces(id, name) values (?, ?)", secondWorkspace, "Second");
    UUID latestFirstWorkspaceJob = UUID.randomUUID();
    UUID secondWorkspaceJob = UUID.randomUUID();
    jdbc.update("""
        insert into indexing_jobs(id, source, status, stage, workspace_id, created_at)
        values (?, ?, 'COMPLETED', 'COMPLETED', ?, now() - interval '1 minute')
        """, UUID.randomUUID(), source, firstWorkspace);
    jdbc.update("""
        insert into indexing_jobs(id, source, object_key, status, stage, workspace_id)
        values (?, ?, ?, 'QUEUED', 'QUEUED', ?)
        """, latestFirstWorkspaceJob, source, source, firstWorkspace);
    jdbc.update("""
        insert into indexing_jobs(id, source, status, stage, workspace_id)
        values (?, ?, 'FAILED', 'FAILED', ?)
        """, secondWorkspaceJob, source, secondWorkspace);

    IndexingJobStore jobs = new IndexingJobStore(jdbc);

    assertThat(jobs.latestFileStatuses(firstWorkspace))
        .extracting(IndexingJobStore.FileIndexingStatus::status)
        .containsExactly("QUEUED");
    assertThat(jobs.latestFileStatuses(firstWorkspace))
        .extracting(IndexingJobStore.FileIndexingStatus::stage)
        .containsExactly(IndexingJobStore.Stage.QUEUED);
    assertThat(jobs.latestFileStatuses(secondWorkspace))
        .extracting(IndexingJobStore.FileIndexingStatus::status)
        .containsExactly("FAILED");
  }

  @Test
  void includesLatestIndexingStatusAndUploadedFilesWithoutJobsInStorageListing() {
    UUID workspaceId = UUID.randomUUID();
    String indexedKey = "uploads/" + workspaceId + "/manual.pdf";
    String uploadedKey = "uploads/" + workspaceId + "/notes.txt";
    JdbcTemplate jdbc = jdbc();
    jdbc.update("insert into workspaces(id, name) values (?, ?)", workspaceId, "Files");
    jdbc.update("insert into workspace_members(workspace_id, subject, role) values (?, ?, 'OWNER')",
        workspaceId, "owner");
    jdbc.update("""
        insert into uploaded_objects(object_key, workspace_id, uploaded_by)
        values (?, ?, 'owner')
        """, indexedKey, workspaceId);
    jdbc.update("""
        insert into indexing_jobs(id, source, object_key, status, stage, workspace_id, review_required)
        values (?, ?, ?, 'PROCESSING', 'SCREENING', ?, true)
        """, UUID.randomUUID(), indexedKey, indexedKey, workspaceId);

    S3ObjectWriter objects = mock(S3ObjectWriter.class);
    when(objects.list("uploads/" + workspaceId + "/")).thenReturn(List.of(
        new S3ObjectWriter.StoredObject(indexedKey, 100),
        new S3ObjectWriter.StoredObject(uploadedKey, 50)));
    DocumentStore documents = mock(DocumentStore.class);
    when(documents.listDocuments(workspaceId)).thenReturn(List.of());
    SpeechTranscription transcription = mock(SpeechTranscription.class);
    StorageQuotaService storage = new StorageQuotaService(
        jdbc, objects, documents, new IndexingJobStore(jdbc), transcription);

    var result = storage.usage("owner", workspaceId);

    assertThat(result.files())
        .extracting(file -> org.assertj.core.groups.Tuple.tuple(
            file.key(), file.status(), file.stage(), file.errorMessage()))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(indexedKey, "PROCESSING", "SCREENING", null),
            org.assertj.core.groups.Tuple.tuple(uploadedKey, "UPLOADED", null, null));
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(dataSource);
  }

  private record RetrievalCase(
      String question, String expectedSource, double[] queryVector, List<EvaluationDocument> documents) {}
  private record EvaluationDocument(String source, String text, double[] vector) {}

  private static float[] vector(float first, float second) {
    float[] values = new float[VECTOR_DIMENSIONS];
    values[0] = first;
    values[1] = second;
    return values;
  }

  private static String legacyVector(float first, float second) {
    float[] values = new float[INITIAL_VECTOR_DIMENSIONS];
    values[0] = first;
    values[1] = second;
    StringBuilder result = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) result.append(',');
      result.append(values[i]);
    }
    return result.append(']').toString();
  }
}
