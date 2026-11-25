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

  public ApiModels.IndexingJobResponse enqueueText(ApiModels.IngestRequest request) {
    return enqueue(jobs.create(request.source(), null, request.text()));
  }

  public ApiModels.IndexingJobResponse enqueueS3(ApiModels.S3IngestRequest request) {
    return enqueue(jobs.create(request.key(), request.key(), null));
  }

  public ApiModels.IndexingJobResponse enqueueReembed() {
    return enqueue(jobs.createReembed());
  }

  public ApiModels.IndexingJobResponse get(UUID id) {
    return jobs.get(id).map(IndexingJobService::toResponse)
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
