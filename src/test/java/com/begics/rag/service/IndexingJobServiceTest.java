package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.storage.S3DocumentReader;
import com.begics.rag.store.IndexingJobStore;
import java.util.UUID;
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
}
