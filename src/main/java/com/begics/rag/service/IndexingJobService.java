package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.store.IndexingJobStore;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import com.begics.rag.storage.S3DocumentReader;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IndexingJobService {
  private static final int DISPATCH_PAGE_SIZE = 100;

  private final IndexingJobStore jobs;
  private final IndexingJobWorker worker;
  private final S3DocumentReader documents;
  private final IndexingJobDispatchTracker dispatchTracker;
  private volatile boolean recoveryComplete;

  @Autowired
  public IndexingJobService(
      IndexingJobStore jobs, IndexingJobWorker worker, S3DocumentReader documents,
      IndexingJobDispatchTracker dispatchTracker) {
    this.jobs = jobs;
    this.worker = worker;
    this.documents = documents;
    this.dispatchTracker = dispatchTracker;
  }

  public IndexingJobService(IndexingJobStore jobs, IndexingJobWorker worker, S3DocumentReader documents) {
    this(jobs, worker, documents, new IndexingJobDispatchTracker());
  }

  public ApiModels.IndexingJobResponse enqueueText(ApiModels.IngestRequest request, UUID workspaceId) {
    return enqueueText(request, workspaceId, "system");
  }

  public ApiModels.IndexingJobResponse enqueueText(
      ApiModels.IngestRequest request, UUID workspaceId, String submittedBy) {
    return enqueue(jobs.create(workspaceId, request.source(), null, request.text(), submittedBy));
  }

  public ApiModels.IndexingJobResponse enqueueS3(ApiModels.S3IngestRequest request, UUID workspaceId) {
    return enqueueS3(request, workspaceId, "system");
  }

  public ApiModels.IndexingJobResponse enqueueS3(
      ApiModels.S3IngestRequest request, UUID workspaceId, String submittedBy) {
    WorkspaceAccessService.requireObjectKey(request.key(), workspaceId);
    documents.validateIndexableSize(request.key());
    return enqueue(jobs.create(workspaceId, request.key(), request.key(), null, submittedBy));
  }

  public ApiModels.IndexingJobResponse enqueueReembed(UUID workspaceId) {
    return enqueue(jobs.createReembed(workspaceId));
  }

  public ApiModels.IndexingJobResponse get(UUID id, UUID workspaceId) {
    return jobs.get(id, workspaceId).map(IndexingJobService::toResponse)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Indexing job not found"));
  }

  public ApiModels.IndexingJobResponse approve(UUID id, UUID workspaceId, String reviewer) {
    if (jobs.get(id, workspaceId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Indexing job not found");
    }
    if (!jobs.approve(id, workspaceId, reviewer)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This job is not awaiting approval");
    }
    schedule(id);
    return get(id, workspaceId);
  }

  public ApiModels.IndexingJobResponse reject(UUID id, UUID workspaceId, String reviewer) {
    if (jobs.get(id, workspaceId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Indexing job not found");
    }
    if (!jobs.reject(id, workspaceId, reviewer)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This job is not awaiting approval");
    }
    return get(id, workspaceId);
  }

  public java.util.List<ApiModels.IndexingJobResponse> recent(UUID workspaceId) {
    return jobs.recent(workspaceId).stream().map(IndexingJobService::toResponse).toList();
  }

  public void recoverPendingJobs() {
    jobs.recoverProcessingJobs();
    recoveryComplete = true;
    dispatchQueuedJobs();
  }

  @Scheduled(fixedDelay = 1000)
  public void retryQueuedJobs() {
    if (recoveryComplete) {
      dispatchQueuedJobs();
    }
  }

  public void dispatchQueuedJobs() {
    while (true) {
      var page = jobs.queuedPage(dispatchTracker.scheduledIds(), DISPATCH_PAGE_SIZE);
      for (UUID id : page) {
        if (!schedule(id, false)) {
          return;
        }
      }
      if (page.size() < DISPATCH_PAGE_SIZE) {
        return;
      }
    }
  }

  private ApiModels.IndexingJobResponse enqueue(IndexingJobStore.IndexingJob job) {
    schedule(job.id());
    return toResponse(job);
  }

  private void schedule(UUID id) {
    schedule(id, true);
  }

  private boolean schedule(UUID id, boolean failOnRejection) {
    return dispatchTracker.dispatch(id, () -> {
      try {
        worker.process(id);
        return true;
      } catch (RejectedExecutionException exception) {
        if (!failOnRejection) {
          return false;
        }
        String message = "Indexing queue is full; retry the request shortly.";
        if (!jobs.failQueued(id, message)) {
          throw exception;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, exception);
      }
    });
  }

  private static ApiModels.IndexingJobResponse toResponse(IndexingJobStore.IndexingJob job) {
    return new ApiModels.IndexingJobResponse(
        job.id(), job.source(), job.status(), job.stage().name(), job.chunks(), job.errorMessage(), job.jobType(),
        job.reviewFindings(), job.screenedAt(), job.submittedBy(),
        job.uploadedBy(), job.uploadedAt(), job.reviewedBy(), job.reviewedAt(),
        job.indexedDocumentId(), job.indexedAt());
  }
}
