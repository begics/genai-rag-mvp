package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.storage.S3DocumentReader;
import com.begics.rag.store.IndexingJobStore;
import java.util.UUID;
import java.util.Optional;
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
