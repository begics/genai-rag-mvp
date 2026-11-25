package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.service.IndexingJobService;
import com.begics.rag.service.RagService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
  public RagController(RagService service, IndexingJobService indexingJobs) {
    this.service = service;
    this.indexingJobs = indexingJobs;
  }
  @PostMapping("/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiModels.IngestResponse ingest(@Valid @RequestBody ApiModels.IngestRequest request) { return service.ingest(request); }
  @PostMapping("/documents/s3")
  @ResponseStatus(HttpStatus.CREATED)
  public ApiModels.IngestResponse ingestFromS3(@Valid @RequestBody ApiModels.S3IngestRequest request) {
    return service.ingestFromS3(request);
  }
  @PostMapping("/indexing-jobs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiModels.IndexingJobResponse enqueueText(@Valid @RequestBody ApiModels.IngestRequest request) {
    return indexingJobs.enqueueText(request);
  }
  @PostMapping("/indexing-jobs/s3")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiModels.IndexingJobResponse enqueueS3(@Valid @RequestBody ApiModels.S3IngestRequest request) {
    return indexingJobs.enqueueS3(request);
  }
  @org.springframework.web.bind.annotation.GetMapping("/indexing-jobs/{id}")
  public ApiModels.IndexingJobResponse getJob(@org.springframework.web.bind.annotation.PathVariable UUID id) {
    return indexingJobs.get(id);
  }
  @PostMapping("/documents/reindex-embeddings")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiModels.IndexingJobResponse reindexMissingEmbeddings() {
    return indexingJobs.enqueueReembed();
  }
  @PostMapping("/query")
  public ApiModels.QueryResponse query(@Valid @RequestBody ApiModels.QueryRequest request) { return service.query(request); }
}
