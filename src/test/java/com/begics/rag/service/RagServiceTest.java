package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.domain.RetrievedChunk;
import com.begics.rag.model.ChatModel;
import com.begics.rag.model.EmbeddingModel;
import com.begics.rag.model.Reranker;
import com.begics.rag.store.DocumentStore;
import com.begics.rag.store.IndexingJobStore.Stage;
import com.begics.rag.storage.S3DocumentReader;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RagServiceTest {
  private final DocumentStore store = Mockito.mock(DocumentStore.class);
  private final S3DocumentReader s3DocumentReader = Mockito.mock(S3DocumentReader.class);
  private final EmbeddingModel embeddings = new EmbeddingModel() {
    @Override
    public float[] embed(String text) {
      return new float[] {1, 0};
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      return texts.stream().map(text -> new float[] {1, 0}).toList();
    }

    @Override
    public String modelId() {
      return "test-model-v1";
    }
  };
  private final ChatModel chat = (question, context) -> "answer";
  private final Reranker reranker = (query, candidates, limit) -> candidates.stream().limit(limit).toList();
  private final RagService service =
      new RagService(store, embeddings, chat, reranker, s3DocumentReader, new TextChunker());

  @Test
  void ingestsChunksAndReturnsStableDocumentId() {
    when(store.save(any(UUID.class), anyString(), anyList(), anyList(), anyString(), any())).thenReturn("doc-1");
    ApiModels.IngestResponse response = service.ingest(new ApiModels.IngestRequest("hello", "source.txt"));
    assertThat(response.documentId()).isEqualTo("doc-1");
    assertThat(response.chunks()).isEqualTo(1);
    verify(store).save(eq(WorkspaceAccessService.LEGACY_WORKSPACE_ID), anyString(), anyList(), anyList(),
        eq("test-model-v1"), eq(null));
  }

  @Test
  void splitsTextAtTheConfiguredChunkBoundary() {
    when(store.save(any(UUID.class), anyString(), anyList(), anyList(), anyString(), any())).thenReturn("doc-1");
    String text = "x".repeat(1_501);

    ApiModels.IngestResponse response = service.ingest(new ApiModels.IngestRequest(text, "source.txt"));

    assertThat(response.chunks()).isEqualTo(2);
    org.mockito.ArgumentCaptor<List<String>> chunks = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(store).save(eq(WorkspaceAccessService.LEGACY_WORKSPACE_ID), eq("source.txt"),
        chunks.capture(), anyList(), eq("test-model-v1"), eq(null));
    assertThat(chunks.getValue()).hasSize(2);
    assertThat(chunks.getValue().get(0)).hasSizeLessThanOrEqualTo(1_500);
    assertThat(chunks.getValue().get(1)).contains(chunks.getValue().get(0).substring(1_300));
  }

  @Test
  void reportsChunkEmbeddingAndStorageStagesInOrder() {
    when(store.save(any(UUID.class), anyString(), anyList(), anyList(), anyString(), any()))
        .thenReturn("doc-progress");
    List<Stage> stages = new java.util.ArrayList<>();

    service.indexText("notes.txt", "searchable text", UUID.randomUUID(), null, stages::add);

    assertThat(stages).containsExactly(Stage.CHUNKING, Stage.EMBEDDING, Stage.SAVING);
  }

  @Test
  void batchesEmbeddingRequestsDuringIngestion() {
    AtomicInteger batchCalls = new AtomicInteger();
    EmbeddingModel batchModel = new EmbeddingModel() {
      @Override
      public float[] embed(String text) {
        throw new AssertionError("Ingestion should use batch embedding");
      }

      @Override
      public List<float[]> embedAll(List<String> texts) {
        batchCalls.incrementAndGet();
        return texts.stream().map(text -> new float[] {1, 0}).toList();
      }
    };
    when(store.save(any(UUID.class), anyString(), anyList(), anyList(), anyString(), any())).thenReturn("doc-batch");
    RagService batchService =
        new RagService(store, batchModel, chat, reranker, s3DocumentReader, new TextChunker());

    ApiModels.IngestResponse response = batchService.ingest(
        new ApiModels.IngestRequest("x".repeat(200_000), "batch.txt"));

    assertThat(response.chunks()).isGreaterThan(100);
    assertThat(batchCalls.get()).isEqualTo(2);
  }

  @Test
  void readsS3TextAndIndexesItWithItsObjectKeyAsSource() {
    String key = "uploads/" + WorkspaceAccessService.LEGACY_WORKSPACE_ID + "/file/notes.txt";
    when(s3DocumentReader.read(key)).thenReturn("text from object storage");
    when(store.save(any(UUID.class), anyString(), anyList(), anyList(), anyString(), any())).thenReturn("doc-2");

    ApiModels.IngestResponse response = service.ingestFromS3(new ApiModels.S3IngestRequest(key));

    assertThat(response.documentId()).isEqualTo("doc-2");
    assertThat(response.chunks()).isEqualTo(1);
    verify(store).save(eq(WorkspaceAccessService.LEGACY_WORKSPACE_ID), eq(key),
        eq(List.of("text from object storage")), anyList(), eq("test-model-v1"), eq(null));
  }

  @Test
  void queriesRetrievedContentAndReferences() {
    String objectKey = "uploads/workspace-id/private-id/source.txt";
    when(store.search(any(UUID.class), anyString(), any(), anyString(), any(Integer.class))).thenReturn(List.of(
        new RetrievedChunk("doc-1", objectKey, 3, "retrieved text", 0.9)));
    ApiModels.QueryResponse response = service.query(new ApiModels.QueryRequest("question"));
    assertThat(response.answer()).isEqualTo("answer");
    assertThat(response.sources()).containsExactly(
        new ApiModels.SourceReference("doc-1", "source.txt", 3, "retrieved text"));
    verify(store).search(eq(WorkspaceAccessService.LEGACY_WORKSPACE_ID),
        eq("question"), any(), eq("test-model-v1"), eq(20));
  }

  @Test
  void forwardsSourceAndDocumentTypeFiltersToRetrieval() {
    when(store.search(any(UUID.class), anyString(), any(), anyString(), any(Integer.class),
        org.mockito.ArgumentMatchers.any(DocumentStore.DocumentFilter.class))).thenReturn(List.of());
    DocumentStore.DocumentFilter filter = new DocumentStore.DocumentFilter("manual.pdf", "pdf");

    service.query(new ApiModels.QueryRequest("question", "manual.pdf", "pdf"),
        WorkspaceAccessService.LEGACY_WORKSPACE_ID, filter);

    verify(store).search(eq(WorkspaceAccessService.LEGACY_WORKSPACE_ID), eq("question"), any(),
        eq("test-model-v1"), eq(20), eq(filter));
  }

  @Test
  void sendsRetrievedCandidatesToTheRerankerBeforeBuildingAnswerContext() {
    RetrievedChunk lessRelevant = new RetrievedChunk("doc-1", "source.txt", 0, "less relevant", 0.9);
    RetrievedChunk moreRelevant = new RetrievedChunk("doc-2", "other.txt", 1, "more relevant", 0.7);
    when(store.search(any(UUID.class), anyString(), any(), anyString(), any(Integer.class)))
        .thenReturn(List.of(lessRelevant, moreRelevant));
    List<RetrievedChunk> reranked = new java.util.ArrayList<>();
    Reranker orderingReranker = (query, candidates, limit) -> {
      reranked.addAll(candidates);
      return List.of(moreRelevant);
    };
    List<String> suppliedContext = new java.util.ArrayList<>();
    RagService rerankedService = new RagService(store, embeddings,
        (question, context) -> {
          suppliedContext.add(context);
          return "answer";
        }, orderingReranker, s3DocumentReader, new TextChunker());

    ApiModels.QueryResponse response = rerankedService.query(new ApiModels.QueryRequest("question"));

    assertThat(reranked).containsExactly(lessRelevant, moreRelevant);
    assertThat(suppliedContext).containsExactly("more relevant\n");
    assertThat(response.sources())
        .containsExactly(new ApiModels.SourceReference("doc-2", "other.txt", 1, "more relevant"));
  }

  @Test
  void abstainsWithoutCallingTheChatModelWhenRetrievalIsEmpty() {
    when(store.search(any(UUID.class), anyString(), any(), anyString(), any(Integer.class))).thenReturn(List.of());
    RagService noMatchService = new RagService(store, embeddings,
        (question, context) -> { throw new AssertionError("chat model must not be called"); },
        reranker, s3DocumentReader, new TextChunker());
    ApiModels.QueryResponse response = noMatchService.query(new ApiModels.QueryRequest("question"));
    assertThat(response.answer()).contains("could not find an answer");
    assertThat(response.sources()).isEmpty();
  }

  @Test
  void reportsStoreFailureRatherThanFabricatingSuccess() {
    when(store.search(any(UUID.class), anyString(), any(), anyString(), any(Integer.class)))
        .thenThrow(new IllegalStateException("database unavailable"));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.query(new ApiModels.QueryRequest("question")))
        .isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");
  }

  @Test
  void reportsProviderFailureRatherThanFabricatingSuccess() {
    when(store.search(any(UUID.class), anyString(), any(), anyString(), any(Integer.class))).thenReturn(List.of(
        new RetrievedChunk("doc-1", "source.txt", 0, "retrieved text", 0.9)));
    RagService failingProviderService = new RagService(store, embeddings,
        (question, context) -> { throw new IllegalStateException("provider unavailable"); },
        reranker, s3DocumentReader, new TextChunker());
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> failingProviderService.query(new ApiModels.QueryRequest("question")))
        .isInstanceOf(IllegalStateException.class).hasMessage("provider unavailable");
  }

  @Test
  void reembedsMissingChunksInBatchesUntilTheStoreIsUpToDate() {
    when(store.findChunksWithStaleEmbeddings(WorkspaceAccessService.LEGACY_WORKSPACE_ID, "test-model-v1", 100))
        .thenReturn(List.of(new DocumentStore.ChunkToEmbed(7, "first"), new DocumentStore.ChunkToEmbed(8, "second")))
        .thenReturn(List.of());

    ApiModels.ReindexResponse response = service.reindexMissingEmbeddings();

    assertThat(response.chunksEmbedded()).isEqualTo(2);
    verify(store).updateEmbedding(WorkspaceAccessService.LEGACY_WORKSPACE_ID, 7, new float[] {1, 0}, "test-model-v1");
    verify(store).updateEmbedding(WorkspaceAccessService.LEGACY_WORKSPACE_ID, 8, new float[] {1, 0}, "test-model-v1");
  }

  @Test
  void reportsEmbeddingProviderFailureDuringReindex() {
    when(store.findChunksWithStaleEmbeddings(WorkspaceAccessService.LEGACY_WORKSPACE_ID, "test-model-v1", 100))
        .thenReturn(List.of(new DocumentStore.ChunkToEmbed(7, "first")));
    EmbeddingModel failingEmbeddings = new EmbeddingModel() {
      @Override
      public float[] embed(String text) {
        throw new IllegalStateException("embedding provider unavailable");
      }

      @Override
      public String modelId() {
        return "test-model-v1";
      }
    };
    RagService failingService = new RagService(
        store, failingEmbeddings, chat, reranker, s3DocumentReader, new TextChunker());

    org.assertj.core.api.Assertions.assertThatThrownBy(failingService::reindexMissingEmbeddings)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("embedding provider unavailable");
  }
}
