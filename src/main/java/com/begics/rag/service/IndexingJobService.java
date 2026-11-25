package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.store.IndexingJobStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IndexingJobService {
  private final IndexingJobStore jobs;
  private final IndexingJobWorker worker;

  public IndexingJobService(IndexingJobStore jobs, IndexingJobWorker worker) {
    this.jobs = jobs;
    this.worker = worker;
  }

  public ApiModels.IndexingJobResponse enqueueText(ApiModels.IngestRequest request, UUID workspaceId) {
    return enqueue(jobs.create(workspaceId, request.source(), null, request.text()));
  }

  public ApiModels.IndexingJobResponse enqueueS3(ApiModels.S3IngestRequest request, UUID workspaceId) {
    WorkspaceAccessService.requireObjectKey(request.key(), workspaceId);
    return enqueue(jobs.create(workspaceId, request.key(), request.key(), null));
  }

  public ApiModels.IndexingJobResponse enqueueReembed(UUID workspaceId) {
    return enqueue(jobs.createReembed(workspaceId));
  }

  public ApiModels.IndexingJobResponse get(UUID id, UUID workspaceId) {
    return jobs.get(id, workspaceId).map(IndexingJobService::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Indexing job not found"));
  }

  public void recoverPendingJobs() {
    jobs.recoverProcessingJobs();
    jobs.queuedIds().forEach(worker::process);
  }

  private ApiModels.IndexingJobResponse enqueue(IndexingJobStore.IndexingJob job) {
    worker.process(job.id());
    return toResponse(job);
  }

  private static ApiModels.IndexingJobResponse toResponse(IndexingJobStore.IndexingJob job) {
    return new ApiModels.IndexingJobResponse(
        job.id(), job.status(), job.chunks(), job.errorMessage());
  }
}
