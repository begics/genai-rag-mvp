package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.storage.S3DocumentReader;
import com.begics.rag.store.IndexingJobStore;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import com.begics.rag.store.IndexingJobStore.Stage;
import java.util.UUID;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class IndexingJobServiceTest {
  private final IndexingJobStore jobs = mock(IndexingJobStore.class);
  private final IndexingJobWorker worker = mock(IndexingJobWorker.class);
  private final S3DocumentReader documents = mock(S3DocumentReader.class);
  private final IndexingJobService service = new IndexingJobService(jobs, worker, documents);

  @Test
  void rejectsOversizedObjectsBeforeCreatingOrStartingAnIndexingJob() {
    UUID workspaceId = UUID.randomUUID();
    String key = "uploads/" + workspaceId + "/large.txt";
    doThrow(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too large"))
        .when(documents).validateIndexableSize(key);

    assertThatThrownBy(() -> service.enqueueS3(new ApiModels.S3IngestRequest(key), workspaceId))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

    verifyNoInteractions(jobs, worker);
  }

  @Test
  void marksJobsFailedAndReturnsServiceUnavailableWhenTheWorkerQueueIsFull() {
    UUID id = UUID.randomUUID();
    IndexingJob queued = new IndexingJob(
        id, "notes.txt", null, "content", "QUEUED", Stage.QUEUED, 0, null, "INDEX", workspaceId);
    when(jobs.create(workspaceId, "notes.txt", null, "content", "owner")).thenReturn(queued);
    doThrow(new RejectedExecutionException("queue full")).when(worker).process(id);
    when(jobs.failQueued(id, "Indexing queue is full; retry the request shortly.")).thenReturn(true);

    assertThatThrownBy(() -> service.enqueueText(
        new ApiModels.IngestRequest("content", "notes.txt"), workspaceId, "owner"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

    verify(jobs).failQueued(id, "Indexing queue is full; retry the request shortly.");
  }

  @Test
  void recoveryLeavesQueuedJobsForRetryWhenTheExecutorIsFull() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(jobs.queuedPage(List.of(), 100)).thenReturn(List.of(first, second));
    doThrow(new RejectedExecutionException("queue full")).when(worker).process(first);

    service.recoverPendingJobs();

    verify(worker).process(first);
    org.mockito.Mockito.verify(worker, org.mockito.Mockito.never()).process(second);
    verifyNoInteractions(documents);
    org.mockito.Mockito.verify(jobs).recoverProcessingJobs();
    org.mockito.Mockito.verify(jobs, org.mockito.Mockito.never()).failQueued(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void retriesPersistedQueuedJobsAfterTheExecutorHasCapacity() {
    UUID id = UUID.randomUUID();
    when(jobs.queuedPage(List.of(), 100)).thenReturn(List.of(id));
    doThrow(new RejectedExecutionException("queue full")).doNothing().when(worker).process(id);

    service.dispatchQueuedJobs();
    service.dispatchQueuedJobs();

    verify(worker, org.mockito.Mockito.times(2)).process(id);
    org.mockito.Mockito.verify(jobs, org.mockito.Mockito.never()).failQueued(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void periodicRecoveryWaitsUntilStartupRecoveryIsComplete() {
    service.retryQueuedJobs();

    verifyNoInteractions(jobs, worker);
  }

  @Test
  void dispatchesPersistedJobsInBoundedExcludedIdPages() {
    List<UUID> firstPage = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      firstPage.add(UUID.randomUUID());
    }
    UUID nextId = UUID.randomUUID();
    when(jobs.queuedPage(List.of(), 100)).thenReturn(firstPage);
    when(jobs.queuedPage(org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 100), org.mockito.ArgumentMatchers.eq(100)))
        .thenReturn(List.of(nextId));
    service.dispatchQueuedJobs();

    org.mockito.Mockito.verify(jobs).queuedPage(List.of(), 100);
    org.mockito.Mockito.verify(jobs).queuedPage(
        org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 100),
        org.mockito.ArgumentMatchers.eq(100));
    org.mockito.Mockito.verify(worker, org.mockito.Mockito.times(101)).process(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void apiSubmissionRetriesAfterAConcurrentRecoveryDispatchIsRejected() throws Exception {
    UUID id = UUID.randomUUID();
    IndexingJob queued = new IndexingJob(
        id, "notes.txt", null, "content", "QUEUED", Stage.QUEUED, 0, null, "INDEX", workspaceId);
    when(jobs.queuedPage(List.of(), 100)).thenReturn(List.of(id));
    CountDownLatch recoverySubmissionStarted = new CountDownLatch(1);
    CountDownLatch releaseRecoverySubmission = new CountDownLatch(1);
    CountDownLatch jobCreated = new CountDownLatch(1);
    AtomicInteger submissions = new AtomicInteger();
    AtomicReference<Throwable> requestFailure = new AtomicReference<>();
    when(jobs.create(workspaceId, "notes.txt", null, "content", "owner")).thenAnswer(invocation -> {
      jobCreated.countDown();
      return queued;
    });
    doAnswer(invocation -> {
      if (submissions.incrementAndGet() == 1) {
        recoverySubmissionStarted.countDown();
        if (!releaseRecoverySubmission.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out waiting to release recovery submission");
        }
      }
      throw new RejectedExecutionException("queue full");
    }).when(worker).process(id);
    when(jobs.failQueued(id, "Indexing queue is full; retry the request shortly.")).thenReturn(true);

    IndexingJobService concurrentService =
        new IndexingJobService(jobs, worker, documents, new IndexingJobDispatchTracker());
    var executor = Executors.newFixedThreadPool(2);
    try {
      var recovery = executor.submit(concurrentService::dispatchQueuedJobs);
      assertThat(recoverySubmissionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Thread request = new Thread(() -> {
        try {
          concurrentService.enqueueText(
              new ApiModels.IngestRequest("content", "notes.txt"), workspaceId, "owner");
        } catch (Throwable exception) {
          requestFailure.set(exception);
        }
      });
      request.start();
      assertThat(jobCreated.await(5, TimeUnit.SECONDS)).isTrue();
      long blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (request.getState() != Thread.State.BLOCKED && System.nanoTime() < blockedDeadline) {
        Thread.yield();
      }
      assertThat(request.getState()).isEqualTo(Thread.State.BLOCKED);
      releaseRecoverySubmission.countDown();
      recovery.get(5, TimeUnit.SECONDS);
      request.join(TimeUnit.SECONDS.toMillis(5));

      assertThat(request.isAlive()).isFalse();
      assertThat(requestFailure.get())
          .isInstanceOf(ResponseStatusException.class)
          .extracting(error -> ((ResponseStatusException) error).getStatusCode())
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      org.mockito.Mockito.verify(worker, org.mockito.Mockito.times(2)).process(id);
      verify(jobs).failQueued(id, "Indexing queue is full; retry the request shortly.");
    } finally {
      releaseRecoverySubmission.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void approvalQueuesTheScreenedJobForIndexing() {
    UUID id = UUID.randomUUID();
    String source = "uploads/" + workspaceId + "/manual.pdf";
    var pending = new IndexingJobStore.IndexingJob(
        id, source, null, "screened text", "AWAITING_APPROVAL", IndexingJobStore.Stage.REVIEW_PENDING,
        0, null, "INDEX", workspaceId, "[]",
        java.time.Instant.now(), "uploader", null, null, null, null, null, null);
    var queued = new IndexingJobStore.IndexingJob(
        id, source, null, "screened text", "QUEUED", IndexingJobStore.Stage.QUEUED,
        0, null, "INDEX", workspaceId, "[]",
        java.time.Instant.now(), "uploader", "reviewer", java.time.Instant.now(), null, null, null, null);
    when(jobs.get(id, workspaceId)).thenReturn(Optional.of(pending), Optional.of(queued));
    when(jobs.approve(id, workspaceId, "reviewer")).thenReturn(true);

    var response = service.approve(id, workspaceId, "reviewer");

    org.assertj.core.api.Assertions.assertThat(response.status()).isEqualTo("QUEUED");
    verify(worker).process(id);
  }

  @Test
  void rejectsOnlyPendingJobsAndDoesNotStartIndexing() {
    UUID id = UUID.randomUUID();
    String source = "uploads/" + workspaceId + "/manual.pdf";
    var rejected = new IndexingJobStore.IndexingJob(
        id, source, null, null, "REJECTED", IndexingJobStore.Stage.REJECTED,
        0, null, "INDEX", workspaceId);
    when(jobs.get(id, workspaceId)).thenReturn(Optional.of(rejected));
    when(jobs.reject(id, workspaceId, "reviewer")).thenReturn(true);

    var response = service.reject(id, workspaceId, "reviewer");

    org.assertj.core.api.Assertions.assertThat(response.status()).isEqualTo("REJECTED");
    verifyNoInteractions(worker);
  }

  private final UUID workspaceId = UUID.randomUUID();
}
