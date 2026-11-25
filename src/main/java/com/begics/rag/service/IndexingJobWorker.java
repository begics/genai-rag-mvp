package com.begics.rag.service;

import com.begics.rag.store.IndexingJobStore;
import com.begics.rag.store.IndexingJobStore.IndexingJob;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class IndexingJobWorker {
  private static final Logger logger = LoggerFactory.getLogger(IndexingJobWorker.class);
  private final IndexingJobStore jobs;
  private final RagService ragService;

  public IndexingJobWorker(IndexingJobStore jobs, RagService ragService) {
    this.jobs = jobs;
    this.ragService = ragService;
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
        jobs.complete(id, ragService.reindexMissingEmbeddings().chunksEmbedded());
      } else {
        String text = job.objectKey() == null ? job.content() : ragService.readS3Document(job.objectKey());
        var result = ragService.indexText(job.source(), text, id);
        jobs.complete(id, result.chunks());
      }
    } catch (RuntimeException exception) {
      String message = exception.getMessage();
      jobs.fail(id, message);
      logger.warn("Indexing job {} failed ({})", id, exception.getClass().getSimpleName());
    }
  }
}
