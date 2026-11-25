package com.begics.rag.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
  public record QueryRequest(@NotBlank @Size(max = 10_000) String question) {}
  public record SourceReference(String documentId, String source, int chunk) {}
  public record QueryResponse(String answer, List<SourceReference> sources) {}
  public record WorkspaceRequest(@NotBlank @Size(max = 120) String name) {}
  public record IndexedDocumentResponse(String id, String source, int chunks) {}
}
