package com.begics.rag.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ApiModels {
  private ApiModels() {}
  public record IngestRequest(@NotBlank @Size(max = 1_000_000) String text,
                              @NotBlank @Size(max = 500) String source) {}
  public record S3IngestRequest(@NotBlank @Size(max = 500) String key) {}
  public record IngestResponse(String documentId, int chunks) {}
  public record ReindexResponse(int chunksEmbedded) {}
  public record IndexingJobResponse(
      UUID id, String source, String status, String stage, int chunks, String errorMessage, String jobType,
      String reviewFindings, Instant screenedAt,
      String submittedBy, String uploadedBy, Instant uploadedAt, String reviewedBy, Instant reviewedAt,
      String indexedDocumentId, Instant indexedAt) {
    public IndexingJobResponse(
        UUID id, String source, String status, String stage, int chunks, String errorMessage, String jobType) {
      this(id, source, status, stage, chunks, errorMessage, jobType, "[]",
          null, null, null, null, null, null, null, null);
    }
  }
  public record UploadResponse(String key, String filename, String contentType, long sizeBytes,
                               boolean indexable) {}
  public record QueryRequest(@NotBlank @Size(max = 10_000) String question,
                             @Size(max = 500) String source,
                             @Size(max = 20) String documentType,
                             @Size(max = 20) String assistantMode) {
    public QueryRequest(String question) {
      this(question, null, null, null);
    }
    public QueryRequest(String question, String source, String documentType) {
      this(question, source, documentType, null);
    }
  }
  public record SourceReference(String documentId, String source, int chunk, String excerpt) {
    public SourceReference(String documentId, String source, int chunk) {
      this(documentId, source, chunk, "");
    }
  }
  public record QueryResponse(String answer, List<SourceReference> sources) {}
  public record AgentQueryRequest(
      @NotBlank @Size(max = 10_000) String question,
      @Size(max = 500) String source,
      @Size(max = 20) String documentType,
      @Size(max = 20) String assistantMode) {}
  public record AgentStepResponse(
      String tool, String status, String title, String detail, long durationMillis) {}
  public record AgentQueryResponse(
      String answer, List<SourceReference> sources, List<AgentStepResponse> steps,
      DocumentCompareResponse comparison, int maxSteps) {}
  public record DocumentContentResponse(String documentId, String filename, String text, int matchOffset) {}
  public record DocumentCompareRequest(
      @NotBlank @Size(max = 500) String firstKey,
      @NotBlank @Size(max = 500) String secondKey) {}
  public record DocumentCompareResponse(
      String firstFilename, String secondFilename, List<String> removedParagraphs,
      List<String> addedParagraphs) {}
  public record InvoiceValidationIssue(String field, String message) {}
  public record InvoiceValidationResponse(List<InvoiceValidationIssue> issues) {}
  public record InvoiceCheckRequest(
      @NotBlank @Size(max = 500) String key,
      @Size(max = 500) String supplier,
      @Size(max = 200) String invoiceNumber,
      LocalDate invoiceDate,
      LocalDate dueDate,
      String currency,
      @jakarta.validation.constraints.DecimalMin("0.0") BigDecimal total) {}
  public record AnswerFeedbackRequest(
      @NotBlank @Size(max = 10_000) String question,
      @NotBlank @Size(max = 50_000) String answer,
      boolean helpful,
      @Size(max = 2_000) String comment,
      @Size(max = 50) List<@Size(max = 500) String> sourceIds) {}
  public record AnswerFeedbackSummaryResponse(
      long totalResponses, long helpfulResponses, long unhelpfulResponses, double helpfulRatePercent) {}
  public record InvoiceExtractionRequest(@NotBlank @Size(max = 500) String key) {}
  public record InvoiceField<T>(T value, String evidence) {}
  public record InvoiceExtractionResponse(
      InvoiceField<String> supplier,
      InvoiceField<String> invoiceNumber,
      InvoiceField<LocalDate> invoiceDate,
      InvoiceField<LocalDate> dueDate,
      InvoiceField<String> currency,
      InvoiceField<BigDecimal> total,
      List<InvoiceValidationIssue> validationIssues) {
    public InvoiceExtractionResponse(
        InvoiceField<String> supplier,
        InvoiceField<String> invoiceNumber,
        InvoiceField<LocalDate> invoiceDate,
        InvoiceField<LocalDate> dueDate,
        InvoiceField<String> currency,
        InvoiceField<BigDecimal> total) {
      this(supplier, invoiceNumber, invoiceDate, dueDate, currency, total, List.of());
    }
  }
  public record WorkspaceRequest(@NotBlank @Size(max = 120) String name) {}
  public record IndexedDocumentResponse(String id, String source, int chunks) {}
  public record PipelineInfoResponse(
      String provider, String embeddingService, String embeddingModel, int embeddingDimensions,
      String rerankService, String rerankModel, String chatService, String chatModel,
      String agentPlanner, String agentTools, int maxAgentSearches,
      Integer chatContextTokens, Integer agentPlannerContextTokens) {}
  public record StorageFileResponse(
      String key, String filename, long sizeBytes, boolean indexed, boolean indexable, int chunks,
      long indexedBytes, String status, String stage, String errorMessage) {}
  public record StorageUsageResponse(
      long s3UsedBytes, long s3LimitBytes, long indexUsedBytes, long indexLimitBytes,
      List<StorageFileResponse> files) {}
}
