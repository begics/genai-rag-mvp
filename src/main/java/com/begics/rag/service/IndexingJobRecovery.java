package com.begics.rag.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class IndexingJobRecovery {
  private final IndexingJobService jobs;

  public IndexingJobRecovery(IndexingJobService jobs) {
    this.jobs = jobs;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void resumePendingJobs() {
    jobs.recoverPendingJobs();
  }
}
