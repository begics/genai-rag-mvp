package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.domain.RetrievedChunk;
import com.begics.rag.model.ChatModel;
import com.begics.rag.model.EmbeddingModel;
import com.begics.rag.model.GroundingVerifier;
import com.begics.rag.model.Reranker;
import com.begics.rag.store.DocumentStore;
import com.begics.rag.storage.S3DocumentReader;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.begics.rag.store.IndexingJobStore.Stage;

@Service
public class RagService {
  private static final int MAX_RESULTS = 5;
  private static final int CANDIDATE_RESULTS = 20;
  private static final int REEMBED_BATCH_SIZE = 100;
  private static final int EMBEDDING_BATCH_SIZE = 100;
  private final DocumentStore store;
  private final EmbeddingModel embeddings;
  private final ChatModel chat;
  private final Reranker reranker;
  private final S3DocumentReader s3DocumentReader;
  private final TextChunker chunker;
  private final RequestGuardrails requestGuardrails;
  private final GroundingVerifier groundingVerifier;

  @Autowired
  public RagService(DocumentStore store, EmbeddingModel embeddings, ChatModel chat,
      Reranker reranker, S3DocumentReader s3DocumentReader, TextChunker chunker,
      RequestGuardrails requestGuardrails, GroundingVerifier groundingVerifier) {
    this.store = store; this.embeddings = embeddings; this.chat = chat;
    this.reranker = reranker;
    this.s3DocumentReader = s3DocumentReader;
    this.chunker = chunker;
    this.requestGuardrails = requestGuardrails;
    this.groundingVerifier = groundingVerifier;
  }

  public RagService(DocumentStore store, EmbeddingModel embeddings, ChatModel chat,
      Reranker reranker, S3DocumentReader s3DocumentReader, TextChunker chunker) {
    this(store, embeddings, chat, reranker, s3DocumentReader, chunker,
        new RequestGuardrails(""), (question, answer, context) -> true);
  }
  public ApiModels.IngestResponse ingest(ApiModels.IngestRequest request, UUID workspaceId) {
    return ingestText(request.source(), request.text(), workspaceId, null);
  }
  public ApiModels.IngestResponse ingestFromS3(ApiModels.S3IngestRequest request, UUID workspaceId) {
    WorkspaceAccessService.requireObjectKey(request.key(), workspaceId);
    String text = readS3Document(request.key());
    return ingestText(request.key(), text, workspaceId, null);
  }

  String readS3Document(String key) {
    String text = s3DocumentReader.read(key);
    if (text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "S3 document contains no extractable text");
    }
    return text;
  }
  public ApiModels.IngestResponse ingest(ApiModels.IngestRequest request) {
    return ingest(request, WorkspaceAccessService.LEGACY_WORKSPACE_ID);
  }

  public ApiModels.IngestResponse ingestFromS3(ApiModels.S3IngestRequest request) {
    return ingestFromS3(request, WorkspaceAccessService.LEGACY_WORKSPACE_ID);
  }

  private ApiModels.IngestResponse ingestText(String source, String text, UUID workspaceId, UUID documentId) {
    return ingestText(source, text, workspaceId, documentId, ignored -> {});
  }

  private ApiModels.IngestResponse ingestText(
      String source, String text, UUID workspaceId, UUID documentId, Consumer<Stage> stageUpdater) {
    stageUpdater.accept(Stage.CHUNKING);
    List<String> chunks = chunker.split(text);
    if (chunks.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document contains no extractable text");
    }
    stageUpdater.accept(Stage.EMBEDDING);
    List<float[]> vectors = embedInBatches(chunks);
    stageUpdater.accept(Stage.SAVING);
    return new ApiModels.IngestResponse(
        store.save(workspaceId, source, chunks, vectors, embeddings.modelId(), documentId), chunks.size());
  }
  public ApiModels.QueryResponse query(ApiModels.QueryRequest request, UUID workspaceId) {
    return query(request, workspaceId, new DocumentStore.DocumentFilter(request.source(), request.documentType()));
  }

  public ApiModels.QueryResponse query(
      ApiModels.QueryRequest request, UUID workspaceId, DocumentStore.DocumentFilter filter) {
    return queryWithSearchQueries(request.question(), List.of(request.question()), workspaceId,
        filter, request.assistantMode());
  }

  public ApiModels.QueryResponse queryWithSearchQueries(
      String question, List<String> searchQueries, UUID workspaceId,
      DocumentStore.DocumentFilter filter, String assistantMode) {
    validateQuestion(question);
    String assistantInstruction = assistantInstruction(assistantMode);
    java.util.LinkedHashMap<String, RetrievedChunk> uniqueCandidates = new java.util.LinkedHashMap<>();
    for (String searchQuery : searchQueries) {
      List<RetrievedChunk> results = filter == null
          || ((filter.source() == null || filter.source().isBlank())
              && (filter.documentType() == null || filter.documentType().isBlank()))
          ? store.search(workspaceId, searchQuery, embeddings.embed(searchQuery),
              embeddings.modelId(), CANDIDATE_RESULTS)
          : store.search(workspaceId, searchQuery, embeddings.embed(searchQuery),
              embeddings.modelId(), CANDIDATE_RESULTS, filter);
      for (RetrievedChunk result : results) {
        uniqueCandidates.putIfAbsent(result.documentId() + ":" + result.chunk(), result);
      }
    }
    List<RetrievedChunk> matches =
        reranker.rerank(question, List.copyOf(uniqueCandidates.values()), MAX_RESULTS);
    if (matches.isEmpty()) {
      return new ApiModels.QueryResponse("I could not find an answer in the indexed sources.", List.of());
    }
    String context = matches.stream().map(RetrievedChunk::content).reduce("", (a, b) -> a + b + "\n");
    List<ApiModels.SourceReference> sources = matches.stream()
        .map(match -> new ApiModels.SourceReference(
            match.documentId(), displayFilename(match.source()), match.chunk(), match.content()))
        .toList();
    String answer = chat.answer(assistantInstruction + question, context);
    if (!groundingVerifier.isGrounded(question, answer, context)) {
      return new ApiModels.QueryResponse(
          "I could not verify that the retrieved evidence supports a reliable answer.", sources);
    }
    return new ApiModels.QueryResponse(answer, sources);
  }

  public void validateQuestion(String question) {
    requestGuardrails.validateQuestion(question);
  }

  private static String assistantInstruction(String mode) {
    if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("general")) {
      return "";
    }
    return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
      case "support" -> "Answer as a product support assistant. Give concise troubleshooting steps and state "
          + "when the indexed sources do not establish a solution. Question: ";
      case "procedure" -> "Answer as a procedure assistant. Present supported steps in order, preserve warnings, "
          + "and do not invent missing steps. Question: ";
      case "summary" -> "Summarize only the retrieved source passages as concise key points and mention important "
          + "qualifications. Question: ";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported assistant mode");
    };
  }

  private static String displayFilename(String source) {
    int separator = source.lastIndexOf('/');
    return separator >= 0 ? source.substring(separator + 1) : source;
  }

  public ApiModels.QueryResponse query(ApiModels.QueryRequest request) {
    return query(request, WorkspaceAccessService.LEGACY_WORKSPACE_ID);
  }

  public ApiModels.ReindexResponse reindexMissingEmbeddings(UUID workspaceId) {
    return reindexMissingEmbeddings(workspaceId, ignored -> {});
  }

  public ApiModels.ReindexResponse reindexMissingEmbeddings(UUID workspaceId, Consumer<Stage> stageUpdater) {
    int total = 0;
    List<DocumentStore.ChunkToEmbed> batch;
    while (!(batch = store.findChunksWithStaleEmbeddings(
        workspaceId, embeddings.modelId(), REEMBED_BATCH_SIZE)).isEmpty()) {
      stageUpdater.accept(Stage.EMBEDDING);
      List<float[]> vectors = embeddings.embedAll(batch.stream().map(DocumentStore.ChunkToEmbed::content).toList());
      if (vectors.size() != batch.size()) {
        throw new IllegalStateException("Embedding provider returned a different number of vectors than inputs");
      }
      stageUpdater.accept(Stage.SAVING);
      for (int i = 0; i < batch.size(); i++) {
        store.updateEmbedding(workspaceId, batch.get(i).id(), vectors.get(i), embeddings.modelId());
        total++;
      }
    }
    return new ApiModels.ReindexResponse(total);
  }

  public ApiModels.ReindexResponse reindexMissingEmbeddings() {
    return reindexMissingEmbeddings(WorkspaceAccessService.LEGACY_WORKSPACE_ID);
  }

  ApiModels.IngestResponse indexText(String source, String text, UUID workspaceId, UUID documentId) {
    return indexText(source, text, workspaceId, documentId, ignored -> {});
  }

  ApiModels.IngestResponse indexText(
      String source, String text, UUID workspaceId, UUID documentId, Consumer<Stage> stageUpdater) {
    return ingestText(source, text, workspaceId, documentId, stageUpdater);
  }

  private List<float[]> embedInBatches(List<String> chunks) {
    List<float[]> vectors = new java.util.ArrayList<>(chunks.size());
    for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
      List<String> batch = chunks.subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size()));
      List<float[]> batchVectors = embeddings.embedAll(batch);
      if (batchVectors.size() != batch.size()) {
        throw new IllegalStateException("Embedding provider returned a different number of vectors than inputs");
      }
      vectors.addAll(batchVectors);
    }
    return vectors;
  }
}
