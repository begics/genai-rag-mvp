package com.begics.rag.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels.ReindexResponse;
import com.begics.rag.store.IndexingJobStore;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import com.begics.rag.store.IndexingJobStore.Stage;
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
        new IndexingJob(id, "embedding-reindex", null, null, "PROCESSING", Stage.READING, 0, null, "REEMBED",
            WorkspaceAccessService.LEGACY_WORKSPACE_ID)));
    when(ragService.reindexMissingEmbeddings(
        org.mockito.ArgumentMatchers.eq(WorkspaceAccessService.LEGACY_WORKSPACE_ID),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(new ReindexResponse(39));

    worker.process(id);

    verify(jobs).updateStage(id, Stage.REINDEXING);
    verify(ragService).reindexMissingEmbeddings(
        org.mockito.ArgumentMatchers.eq(WorkspaceAccessService.LEGACY_WORKSPACE_ID),
        org.mockito.ArgumentMatchers.any());
    verify(jobs).complete(id, 39);
  }
}
