package com.begics.rag.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
      UUID id, String source, String status, String stage, int chunks, String errorMessage, String jobType) {}
  public record UploadResponse(String key, String filename, String contentType, long sizeBytes,
                               boolean indexable) {}
  public record QueryRequest(@NotBlank @Size(max = 10_000) String question,
                             @Size(max = 500) String source,
                             @Size(max = 20) String documentType) {
    public QueryRequest(String question) {
      this(question, null, null);
    }
  }
  public record SourceReference(String documentId, String source, int chunk, String excerpt) {
    public SourceReference(String documentId, String source, int chunk) {
      this(documentId, source, chunk, "");
    }
  }
  public record QueryResponse(String answer, List<SourceReference> sources) {}
  public record AnswerFeedbackRequest(
      @NotBlank @Size(max = 10_000) String question,
      @NotBlank @Size(max = 50_000) String answer,
      boolean helpful,
      @Size(max = 2_000) String comment,
      List<@Size(max = 500) String> sourceIds) {}
  public record InvoiceExtractionRequest(@NotBlank @Size(max = 500) String key) {}
  public record InvoiceField<T>(T value, String evidence) {}
  public record InvoiceExtractionResponse(
      InvoiceField<String> supplier,
      InvoiceField<String> invoiceNumber,
      InvoiceField<LocalDate> invoiceDate,
      InvoiceField<LocalDate> dueDate,
      InvoiceField<String> currency,
      InvoiceField<BigDecimal> total) {}
  public record WorkspaceRequest(@NotBlank @Size(max = 120) String name) {}
  public record IndexedDocumentResponse(String id, String source, int chunks) {}
  public record PipelineInfoResponse(
      String provider, String embeddingService, String embeddingModel, int embeddingDimensions,
      String rerankService, String rerankModel, String chatService, String chatModel) {}
  public record StorageFileResponse(
      String key, String filename, long sizeBytes, boolean indexed, boolean indexable, int chunks,
      long indexedBytes) {}
  public record StorageUsageResponse(
      long s3UsedBytes, long s3LimitBytes, long indexUsedBytes, long indexLimitBytes,
      List<StorageFileResponse> files) {}
}
