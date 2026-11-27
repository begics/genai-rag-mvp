package com.begics.rag.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels.ReindexResponse;
import com.begics.rag.contract.ApiModels.IngestResponse;
import com.begics.rag.model.DocumentRiskScreening;
import com.begics.rag.store.IndexingJobStore;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import com.begics.rag.store.IndexingJobStore.Stage;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
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

  @Test
  void screensDocumentTextAndWaitsForApprovalWithoutIndexing() {
    UUID id = UUID.randomUUID();
    String key = "uploads/" + WorkspaceAccessService.LEGACY_WORKSPACE_ID + "/file/notes.txt";
    DocumentRiskScreening screening = Mockito.mock(DocumentRiskScreening.class);
    IndexingJobWorker screeningWorker =
        new IndexingJobWorker(jobs, ragService, screening, new ObjectMapper());
    when(jobs.markProcessing(id)).thenReturn(true);
    when(jobs.get(id)).thenReturn(Optional.of(new IndexingJob(
        id, key, key, null, "PROCESSING", Stage.READING, 0, null, "INDEX",
        WorkspaceAccessService.LEGACY_WORKSPACE_ID)));
    when(ragService.readS3Document(key)).thenReturn("Source text with a payment request.");
    when(screening.evaluate("Source text with a payment request.")).thenReturn(
        new DocumentRiskScreening.ScreeningResult(java.util.List.of(new DocumentRiskScreening.Finding(
            "PAYMENT_REDIRECTION", "payment request", "Not available in extracted text",
            "The recipient and reason for the requested payment are not established."))));

    screeningWorker.process(id);

    verify(jobs).saveScreeningText(id, "Source text with a payment request.");
    verify(jobs).updateStage(id, Stage.SCREENING);
    verify(jobs).awaitApproval(id, "Source text with a payment request.",
        "[{\"category\":\"PAYMENT_REDIRECTION\",\"evidence\":\"payment request\","
            + "\"location\":\"Not available in extracted text\","
            + "\"uncertainty\":\"The recipient and reason for the requested payment are not established.\"}]");
    verify(ragService).readS3Document(key);
    Mockito.verifyNoMoreInteractions(ragService);
  }

  @Test
  void indexesOnlyAPreviouslyScreenedAndApprovedJob() {
    UUID id = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    DocumentRiskScreening screening = Mockito.mock(DocumentRiskScreening.class);
    IndexingJobWorker screeningWorker =
        new IndexingJobWorker(jobs, ragService, screening, new ObjectMapper());
    when(jobs.markProcessing(id)).thenReturn(true);
    when(jobs.get(id)).thenReturn(Optional.of(new IndexingJob(
        id, "reviewed.txt", null, "approved source text", "PROCESSING", Stage.READING, 0, null, "INDEX",
        workspaceId, "[]", java.time.Instant.now(),
        "uploader", "owner", java.time.Instant.now(), null, null, null, null)));
    when(ragService.indexText(
        org.mockito.ArgumentMatchers.eq("reviewed.txt"), org.mockito.ArgumentMatchers.eq("approved source text"),
        org.mockito.ArgumentMatchers.eq(workspaceId), org.mockito.ArgumentMatchers.eq(id),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(new IngestResponse("document-id", 2));

    screeningWorker.process(id);

    verify(ragService).indexText(
        org.mockito.ArgumentMatchers.eq("reviewed.txt"), org.mockito.ArgumentMatchers.eq("approved source text"),
        org.mockito.ArgumentMatchers.eq(workspaceId), org.mockito.ArgumentMatchers.eq(id),
        org.mockito.ArgumentMatchers.any());
    verify(jobs).complete(id, "document-id", 2);
    Mockito.verifyNoInteractions(screening);
  }
}
