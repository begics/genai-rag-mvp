package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.service.IndexingJobService;
import com.begics.rag.service.InvoiceExtractionService;
import com.begics.rag.service.RagService;
import com.begics.rag.service.WorkspaceAccessService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RagController {
  private final RagService service;
  private final IndexingJobService indexingJobs;
  private final InvoiceExtractionService invoiceExtraction;
  private final WorkspaceAccessService workspaces;
  public RagController(RagService service, IndexingJobService indexingJobs,
      InvoiceExtractionService invoiceExtraction, WorkspaceAccessService workspaces) {
    this.service = service;
    this.indexingJobs = indexingJobs;
    this.invoiceExtraction = invoiceExtraction;
    this.workspaces = workspaces;
  }
  @PostMapping("/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiModels.IngestResponse ingest(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.IngestRequest request) {
    return service.ingest(request, workspaces.resolve(authentication, workspace));
  }
  @PostMapping("/documents/s3")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiModels.IngestResponse ingestFromS3(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.S3IngestRequest request) {
    UUID workspaceId = workspaces.resolve(authentication, workspace);
    return service.ingestFromS3(request, workspaceId);
  }
  @PostMapping("/indexing-jobs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiModels.IndexingJobResponse enqueueText(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.IngestRequest request) {
    return indexingJobs.enqueueText(request, workspaces.resolve(authentication, workspace));
  }
  @PostMapping("/indexing-jobs/s3")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiModels.IndexingJobResponse enqueueS3(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.S3IngestRequest request) {
    return indexingJobs.enqueueS3(request, workspaces.resolve(authentication, workspace));
  }
  @org.springframework.web.bind.annotation.GetMapping("/indexing-jobs")
  public java.util.List<ApiModels.IndexingJobResponse> listIndexingJobs(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace) {
    return indexingJobs.recent(workspaces.resolve(authentication, workspace));
  }
  @org.springframework.web.bind.annotation.GetMapping("/indexing-jobs/{id}")
  public ApiModels.IndexingJobResponse getJob(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @org.springframework.web.bind.annotation.PathVariable UUID id) {
    return indexingJobs.get(id, workspaces.resolve(authentication, workspace));
  }
  @PostMapping("/documents/reindex-embeddings")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiModels.IndexingJobResponse reindexMissingEmbeddings(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace) {
    return indexingJobs.enqueueReembed(workspaces.resolve(authentication, workspace));
  }
  @PostMapping("/query")
  public ApiModels.QueryResponse query(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.QueryRequest request) {
    return service.query(request, workspaces.resolve(authentication, workspace));
  }

  @PostMapping("/documents/extract-invoice")
  public ApiModels.InvoiceExtractionResponse extractInvoice(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.InvoiceExtractionRequest request) {
    return invoiceExtraction.extract(request.key(), workspaces.resolve(authentication, workspace));
  }
}
