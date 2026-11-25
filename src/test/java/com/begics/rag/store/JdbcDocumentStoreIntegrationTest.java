package com.begics.rag.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.begics.rag.contract.ApiModels.SourceReference;
import com.begics.rag.domain.RetrievedChunk;
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
import java.io.IOException;
import java.io.InputStream;

@Testcontainers(disabledWithoutDocker = true)
class JdbcDocumentStoreIntegrationTest {
  private static final int INITIAL_VECTOR_DIMENSIONS = 1_536;
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
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .placeholders(Map.of("embeddingDimensions", Integer.toString(INITIAL_VECTOR_DIMENSIONS)))
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("truncate table documents cascade");
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
  void migratesExistingChunksWithoutRetainingLegacyVectors() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion("2"))
        .placeholders(Map.of("embeddingDimensions", Integer.toString(INITIAL_VECTOR_DIMENSIONS)))
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
        .placeholders(Map.of("embeddingDimensions", Integer.toString(INITIAL_VECTOR_DIMENSIONS)))
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
    assertThat(currentType).isEqualTo("halfvec(3584)");
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
        "job-source.txt", null, "content for indexing");
    IndexingJobStore.IndexingJob reembed = jobs.createReembed();

    assertThat(created.status()).isEqualTo("QUEUED");
    assertThat(reembed.jobType()).isEqualTo("REEMBED");
    assertThat(reembed.objectKey()).isNull();
    assertThat(reembed.content()).isNull();
    assertThat(jobs.markProcessing(created.id())).isTrue();
    jobs.recoverProcessingJobs();
    assertThat(jobs.get(created.id()).orElseThrow().status()).isEqualTo("QUEUED");
    assertThat(jobs.markProcessing(created.id())).isTrue();
    jobs.complete(created.id(), 3);
    assertThat(jobs.get(created.id()).orElseThrow().chunks()).isEqualTo(3);
    assertThat(jobs.get(created.id()).orElseThrow().status()).isEqualTo("COMPLETED");
    assertThat(jobs.get(created.id()).orElseThrow().content()).isNull();
    assertThat(jobs.markProcessing(reembed.id())).isTrue();
    jobs.complete(reembed.id(), 39);
    assertThat(jobs.get(reembed.id()).orElseThrow().status()).isEqualTo("COMPLETED");
    assertThat(jobs.get(reembed.id()).orElseThrow().chunks()).isEqualTo(39);
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
