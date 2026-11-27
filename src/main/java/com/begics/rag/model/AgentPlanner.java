package com.begics.rag.model;

import java.util.List;

public interface AgentPlanner {
  AgentPlan plan(String question, List<AgentDocument> documents);

  record AgentPlan(
      List<String> searchQueries,
      String firstDocumentId,
      String secondDocumentId,
      String comparisonRationale) {}

  record AgentDocument(String id, String filename, String type) {}
}
