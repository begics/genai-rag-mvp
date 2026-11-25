package com.begics.rag.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels.ReindexResponse;
import com.begics.rag.store.IndexingJobStore;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class IndexingJobWorkerTest {
  private final IndexingJobStore jobs = Mockito.mock(IndexingJobStore.class);
  private final RagService ragService = Mockito.mock(RagService.class);
  private final IndexingJobWorker worker = new IndexingJobWorker(jobs, ragService);

  @Test
  void runsReembeddingJobsAndPersistsTheEmbeddedChunkCount() {
    UUID id = UUID.randomUUID();
    when(jobs.markProcessing(id)).thenReturn(true);
    when(jobs.get(id)).thenReturn(Optional.of(
        new IndexingJob(id, "embedding-reindex", null, null, "PROCESSING", 0, null, "REEMBED",
            WorkspaceAccessService.LEGACY_WORKSPACE_ID)));
    when(ragService.reindexMissingEmbeddings(WorkspaceAccessService.LEGACY_WORKSPACE_ID))
        .thenReturn(new ReindexResponse(39));

    worker.process(id);

    verify(ragService).reindexMissingEmbeddings(WorkspaceAccessService.LEGACY_WORKSPACE_ID);
    verify(jobs).complete(id, 39);
  }
}
