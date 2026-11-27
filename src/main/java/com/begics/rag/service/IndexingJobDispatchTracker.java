package com.begics.rag.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
class IndexingJobDispatchTracker {
  private final Set<UUID> scheduled = new HashSet<>();

  synchronized List<UUID> scheduledIds() {
    return List.copyOf(scheduled);
  }

  synchronized boolean dispatch(UUID id, BooleanSupplier submission) {
    if (!scheduled.add(id)) {
      return true;
    }
    try {
      boolean accepted = submission.getAsBoolean();
      if (!accepted) {
        scheduled.remove(id);
      }
      return accepted;
    } catch (RuntimeException | Error exception) {
      scheduled.remove(id);
      throw exception;
    }
  }

  synchronized void finished(UUID id) {
    scheduled.remove(id);
  }
}
