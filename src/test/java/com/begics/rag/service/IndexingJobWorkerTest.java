package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels.ReindexResponse;
import com.begics.rag.contract.ApiModels.IngestResponse;
import com.begics.rag.model.DocumentRiskScreening;
import com.begics.rag.store.IndexingJobStore;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import com.begics.rag.store.IndexingJobStore.Stage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    when(ragService.readS3Document(eq(key), any())).thenReturn("Source text with a payment request.");
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
    verify(ragService).readS3Document(eq(key), any());
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

  @Test
  void advancesAnUploadedDocumentThroughReviewAndEveryIndexingPhase() {
    UUID id = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    String key = "uploads/" + workspaceId + "/file/notes.pdf";
    String text = "Approved synthetic source text.";
    DocumentRiskScreening screening = Mockito.mock(DocumentRiskScreening.class);
    IndexingJobWorker screeningWorker =
        new IndexingJobWorker(jobs, ragService, screening, new ObjectMapper());
    IndexingJob queued = new IndexingJob(
        id, key, key, null, "PROCESSING", Stage.READING, 0, null, "INDEX", workspaceId);
    IndexingJob approved = new IndexingJob(
        id, key, null, text, "PROCESSING", Stage.READING, 0, null, "INDEX", workspaceId,
        "[]", Instant.now(), "uploader", "owner", Instant.now(), null, null, null, null);
    List<Stage> stages = new ArrayList<>();
    when(jobs.markProcessing(id)).thenReturn(true);
    when(jobs.get(id)).thenReturn(Optional.of(queued), Optional.of(approved));
    when(ragService.readS3Document(eq(key), any())).thenAnswer(invocation -> {
      java.util.function.Consumer<com.begics.rag.storage.S3DocumentReader.ReadStage> updateStage =
          invocation.getArgument(1);
      updateStage.accept(com.begics.rag.storage.S3DocumentReader.ReadStage.OCR);
      updateStage.accept(com.begics.rag.storage.S3DocumentReader.ReadStage.VISUAL_ANALYSIS);
      updateStage.accept(com.begics.rag.storage.S3DocumentReader.ReadStage.ORIGINAL_REVIEW);
      return text;
    });
    when(screening.evaluate(text))
        .thenReturn(new DocumentRiskScreening.ScreeningResult(List.of()));
    when(ragService.indexText(eq(key), eq(text), eq(workspaceId), eq(id), any()))
        .thenAnswer(invocation -> {
          java.util.function.Consumer<Stage> updateStage = invocation.getArgument(4);
          updateStage.accept(Stage.CHUNKING);
          updateStage.accept(Stage.EMBEDDING);
          updateStage.accept(Stage.SAVING);
          return new IngestResponse("document-id", 3);
        });
    Mockito.doAnswer(invocation -> {
      stages.add(invocation.getArgument(1));
      return null;
    }).when(jobs).updateStage(eq(id), any(Stage.class));

    screeningWorker.process(id);
    screeningWorker.process(id);

    assertThat(stages).containsExactly(
        Stage.READING, Stage.OCR, Stage.VISUAL_ANALYSIS, Stage.ORIGINAL_REVIEW,
        Stage.SCREENING, Stage.CHUNKING, Stage.EMBEDDING, Stage.SAVING);
    verify(jobs).saveScreeningText(id, text);
    verify(screening).evaluate(text);
    verify(jobs).awaitApproval(id, text, "[]");
    verify(ragService).indexText(eq(key), eq(text), eq(workspaceId), eq(id), any());
    verify(jobs).complete(id, "document-id", 3);
  }

  @Test
  void failsTheJobWhenReadingFailsBeforeScreening() {
    UUID id = UUID.randomUUID();
    String key = "uploads/" + WorkspaceAccessService.LEGACY_WORKSPACE_ID + "/file/notes.txt";
    DocumentRiskScreening screening = Mockito.mock(DocumentRiskScreening.class);
    IndexingJobWorker screeningWorker =
        new IndexingJobWorker(jobs, ragService, screening, new ObjectMapper());
    when(jobs.markProcessing(id)).thenReturn(true);
    when(jobs.get(id)).thenReturn(Optional.of(new IndexingJob(
        id, key, key, null, "PROCESSING", Stage.READING, 0, null, "INDEX",
        WorkspaceAccessService.LEGACY_WORKSPACE_ID)));
    when(ragService.readS3Document(eq(key), any())).thenThrow(new IllegalStateException("malware scan failed"));

    screeningWorker.process(id);

    verify(jobs).fail(id, "malware scan failed");
    Mockito.verifyNoInteractions(screening);
    Mockito.verify(ragService).readS3Document(eq(key), any());
    Mockito.verify(ragService, Mockito.never()).indexText(
        any(), any(), any(), any(), any());
  }

  @Test
  void failsTheJobWhenScreeningFailsWithoutAllowingApprovalOrIndexing() {
    UUID id = UUID.randomUUID();
    String key = "uploads/" + WorkspaceAccessService.LEGACY_WORKSPACE_ID + "/file/notes.txt";
    String text = "Unreviewed source text.";
    DocumentRiskScreening screening = Mockito.mock(DocumentRiskScreening.class);
    IndexingJobWorker screeningWorker =
        new IndexingJobWorker(jobs, ragService, screening, new ObjectMapper());
    when(jobs.markProcessing(id)).thenReturn(true);
    when(jobs.get(id)).thenReturn(Optional.of(new IndexingJob(
        id, key, key, null, "PROCESSING", Stage.READING, 0, null, "INDEX",
        WorkspaceAccessService.LEGACY_WORKSPACE_ID)));
    when(ragService.readS3Document(eq(key), any())).thenReturn(text);
    when(screening.evaluate(text)).thenThrow(new IllegalStateException("screening unavailable"));

    screeningWorker.process(id);

    verify(jobs).fail(id, "screening unavailable");
    verify(jobs).saveScreeningText(id, text);
    Mockito.verify(jobs, Mockito.never()).awaitApproval(any(), any(), any());
    Mockito.verify(ragService, Mockito.never()).indexText(any(), any(), any(), any(), any());
  }

  @Test
  void marksTranscribableUploadsAsTranscribingBeforeScreening() {
    UUID id = UUID.randomUUID();
    String key = "uploads/" + WorkspaceAccessService.LEGACY_WORKSPACE_ID + "/file/interview.mp3";
    DocumentRiskScreening screening = Mockito.mock(DocumentRiskScreening.class);
    IndexingJobWorker screeningWorker =
        new IndexingJobWorker(jobs, ragService, screening, new ObjectMapper());
    when(jobs.markProcessing(id)).thenReturn(true);
    when(jobs.get(id)).thenReturn(Optional.of(new IndexingJob(
        id, key, key, null, "PROCESSING", Stage.READING, 0, null, "INDEX",
        WorkspaceAccessService.LEGACY_WORKSPACE_ID)));
    when(ragService.readS3Document(eq(key), any())).thenReturn("Synthetic transcript.");
    when(screening.evaluate("Synthetic transcript."))
        .thenReturn(new DocumentRiskScreening.ScreeningResult(List.of()));

    screeningWorker.process(id);

    Mockito.inOrder(jobs).verify(jobs).updateStage(id, Stage.TRANSCRIBING);
    verify(jobs).updateStage(id, Stage.SCREENING);
    verify(jobs).awaitApproval(id, "Synthetic transcript.", "[]");
  }
}
