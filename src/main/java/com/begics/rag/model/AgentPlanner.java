package com.begics.rag.model;

import java.util.List;

public interface AgentPlanner {
  AgentPlan plan(String question, List<AgentDocument> documents);

  final class InvalidPlanException extends IllegalStateException {
    public InvalidPlanException(String message) {
      super(message);
    }

    public InvalidPlanException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  record AgentPlan(
      List<String> searchQueries,
      String firstDocumentId,
      String secondDocumentId,
      String comparisonRationale) {}

  record AgentDocument(String id, String filename, String type) {}
}
