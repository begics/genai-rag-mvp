package com.begics.rag.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
class IndexingJobDispatchTracker {
  private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();

  boolean trySchedule(UUID id) {
    return scheduled.add(id);
  }

  void finished(UUID id) {
    scheduled.remove(id);
  }
}
