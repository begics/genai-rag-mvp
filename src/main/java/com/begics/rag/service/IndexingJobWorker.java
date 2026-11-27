package com.begics.rag.service;

import com.begics.rag.store.IndexingJobStore;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import com.begics.rag.store.IndexingJobStore.Stage;
import com.begics.rag.model.DocumentRiskScreening;
import com.begics.rag.storage.S3DocumentReader;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class IndexingJobWorker {
  private static final Logger logger = LoggerFactory.getLogger(IndexingJobWorker.class);
  private final IndexingJobStore jobs;
  private final RagService ragService;
  private final DocumentRiskScreening screening;
  private final ObjectMapper objectMapper;

  @Autowired
  public IndexingJobWorker(
      IndexingJobStore jobs, RagService ragService, DocumentRiskScreening screening, ObjectMapper objectMapper) {
    this.jobs = jobs;
    this.ragService = ragService;
    this.screening = screening;
    this.objectMapper = objectMapper;
  }

  public IndexingJobWorker(IndexingJobStore jobs, RagService ragService) {
    this(jobs, ragService, text -> {
      throw new IllegalStateException("Document screening is not configured");
    }, new ObjectMapper());
  }

  @Async("indexingTaskExecutor")
  public void process(UUID id) {
    if (!jobs.markProcessing(id)) {
      return;
    }
    try {
      IndexingJob job = jobs.get(id)
          .orElseThrow(() -> new IllegalStateException("Indexing job disappeared: " + id));
      if ("REEMBED".equals(job.jobType())) {
        jobs.updateStage(id, Stage.REINDEXING);
        jobs.complete(id, ragService.reindexMissingEmbeddings(job.workspaceId(), stage -> jobs.updateStage(id, stage))
            .chunksEmbedded());
      } else {
        String text;
        if (job.content() != null) {
          text = job.content();
        } else {
          jobs.updateStage(id, S3DocumentReader.isTranscribable(job.objectKey())
              ? Stage.TRANSCRIBING : Stage.READING);
          text = ragService.readS3Document(job.objectKey());
          jobs.saveScreeningText(id, text);
        }
        if (job.screenedAt() == null) {
          jobs.updateStage(id, Stage.SCREENING);
          DocumentRiskScreening.ScreeningResult result = screening.evaluate(text);
          String findings;
          try {
            findings = objectMapper.writeValueAsString(result.findings());
          } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize document screening findings", exception);
          }
          jobs.awaitApproval(id, text, findings);
        } else {
          if (job.reviewedAt() == null) {
            throw new IllegalStateException("Indexing job has not been approved");
          }
          var result = ragService.indexText(
              job.source(), text, job.workspaceId(), id, stage -> jobs.updateStage(id, stage));
          jobs.complete(id, result.documentId(), result.chunks());
        }
      }
    } catch (RuntimeException exception) {
      String message = exception.getMessage();
      jobs.fail(id, message);
      logger.warn("Indexing job {} failed ({})", id, exception.getClass().getSimpleName());
    }
  }
}
