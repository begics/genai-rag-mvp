package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.model.AgentPlanner;
import com.begics.rag.store.DocumentStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentService {
  private static final int MAX_SEARCH_QUERIES = 3;
  private static final int MAX_WORKSPACE_DOCUMENTS_FOR_PLANNING = 100;
  private static final int MAX_PLAN_STEPS = 6;

  private final AgentPlanner planner;
  private final DocumentStore documents;
  private final RagService rag;
  private final DocumentCompareService comparison;

  public AgentService(
      AgentPlanner planner, DocumentStore documents, RagService rag, DocumentCompareService comparison) {
    this.planner = planner;
    this.documents = documents;
    this.rag = rag;
    this.comparison = comparison;
  }

  public ApiModels.AgentQueryResponse query(ApiModels.AgentQueryRequest request, UUID workspaceId) {
    long planningStarted = System.nanoTime();
    List<DocumentStore.IndexedDocument> indexedDocuments = documents.listDocuments(workspaceId);
    List<AgentPlanner.AgentDocument> plannerDocuments = indexedDocuments.stream()
        .filter(document -> document.chunks() > 0)
        .limit(MAX_WORKSPACE_DOCUMENTS_FOR_PLANNING)
        .map(document -> new AgentPlanner.AgentDocument(
            document.id(), filename(document.source()), extension(document.source())))
        .toList();
    AgentPlanner.AgentPlan plan = planner.plan(request.question(), plannerDocuments);
    List<String> queries = validateSearchQueries(plan);
    List<ApiModels.AgentStepResponse> steps = new ArrayList<>();
    steps.add(step("planner", "completed", "Plan the task",
        "Selected " + queries.size() + (queries.size() == 1 ? " workspace search query" : " workspace search queries")
            + " and checked whether a document comparison is needed.",
        planningStarted));

    ApiModels.DocumentCompareResponse compareResult =
        executeComparison(plan, indexedDocuments, workspaceId, steps);
    long searchStarted = System.nanoTime();
    ApiModels.QueryResponse answer = rag.queryWithSearchQueries(
        request.question(), queries, workspaceId,
        new DocumentStore.DocumentFilter(request.source(), request.documentType()),
        request.assistantMode());
    for (String query : queries) {
      steps.add(new ApiModels.AgentStepResponse(
          "workspace-search", "completed", "Search workspace knowledge",
          "Planned query: " + query + ". Executed in the bounded combined search run.", 0));
    }
    steps.add(step("evidence-and-answer", "completed", "Retrieve, rank evidence, and answer",
        answer.sources().isEmpty()
            ? "Executed all planned searches; no supporting passages were found, so the assistant abstained."
            : "Executed all planned searches, merged and reranked passages, then generated an answer with source references.",
        searchStarted));
    return new ApiModels.AgentQueryResponse(answer.answer(), answer.sources(), List.copyOf(steps),
        compareResult, MAX_PLAN_STEPS);
  }

  private ApiModels.DocumentCompareResponse executeComparison(
      AgentPlanner.AgentPlan plan, List<DocumentStore.IndexedDocument> indexedDocuments,
      UUID workspaceId, List<ApiModels.AgentStepResponse> steps) {
    if (plan.firstDocumentId() == null && plan.secondDocumentId() == null) {
      steps.add(new ApiModels.AgentStepResponse(
          "document-compare", "not-needed", "Compare documents",
          "Not selected for this question.", 0));
      return null;
    }
    long started = System.nanoTime();
    Map<String, DocumentStore.IndexedDocument> byId = indexedDocuments.stream()
        .collect(Collectors.toMap(DocumentStore.IndexedDocument::id, Function.identity(), (first, ignored) -> first));
    DocumentStore.IndexedDocument first = byId.get(plan.firstDocumentId());
    DocumentStore.IndexedDocument second = byId.get(plan.secondDocumentId());
    if (first == null || second == null || first.id().equals(second.id())
        || !isComparable(first.source()) || !isComparable(second.source())) {
      steps.add(step("document-compare", "skipped", "Compare documents",
          "Skipped because the plan did not identify two different indexed text, Markdown, CSV, or PDF files in this workspace.",
          started));
      return null;
    }
    ApiModels.DocumentCompareResponse result = comparison.compare(
        new ApiModels.DocumentCompareRequest(first.source(), second.source()), workspaceId);
    String rationale = plan.comparisonRationale() == null ? "" : plan.comparisonRationale().strip();
    if (rationale.length() > 240) rationale = rationale.substring(0, 240);
    steps.add(step("document-compare", "completed", "Compare documents",
        rationale.isBlank()
            ? "Compared exact paragraphs in the two workspace documents."
            : rationale + " Exact paragraph comparison was used.",
        started));
    return result;
  }

  private static List<String> validateSearchQueries(AgentPlanner.AgentPlan plan) {
    if (plan == null || plan.searchQueries() == null || plan.searchQueries().isEmpty()
        || plan.searchQueries().size() > MAX_SEARCH_QUERIES) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent planner returned an invalid search plan");
    }
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String query : plan.searchQueries()) {
      if (query == null || query.isBlank() || query.length() > 500) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent planner returned an invalid search query");
      }
      unique.add(query.strip());
    }
    if (unique.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent planner returned an empty search plan");
    }
    return List.copyOf(unique);
  }

  private static boolean isComparable(String source) {
    String lower = source.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".txt") || lower.endsWith(".md")
        || lower.endsWith(".csv") || lower.endsWith(".pdf");
  }

  private static String filename(String source) {
    return source.substring(source.lastIndexOf('/') + 1);
  }

  private static String extension(String source) {
    int dot = source.lastIndexOf('.');
    return dot < 0 ? "" : source.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
  }

  private static ApiModels.AgentStepResponse step(
      String tool, String status, String title, String detail, long startedNanos) {
    return new ApiModels.AgentStepResponse(
        tool, status, title, detail, Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
  }
}
