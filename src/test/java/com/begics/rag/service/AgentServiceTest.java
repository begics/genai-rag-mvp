package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.model.AgentPlanner;
import com.begics.rag.store.DocumentStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class AgentServiceTest {
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private final AgentPlanner planner = Mockito.mock(AgentPlanner.class);
  private final DocumentStore documents = Mockito.mock(DocumentStore.class);
  private final RagService rag = Mockito.mock(RagService.class);
  private final DocumentCompareService comparison = Mockito.mock(DocumentCompareService.class);
  private final AgentService service = new AgentService(planner, documents, rag, comparison);

  @Test
  void executesPlannerSearchPlanAndReturnsVisibleTrace() {
    when(documents.listDocuments(WORKSPACE_ID)).thenReturn(List.of(
        new DocumentStore.IndexedDocument("doc-1", "uploads/" + WORKSPACE_ID + "/manual.txt", 2, 100)));
    when(planner.plan(eq("How does setup work?"), any())).thenReturn(
        new AgentPlanner.AgentPlan(List.of("setup overview", "setup prerequisites"), null, null, null));
    when(rag.queryWithSearchQueries(eq("How does setup work?"),
        eq(List.of("setup overview", "setup prerequisites")), eq(WORKSPACE_ID),
        any(DocumentStore.DocumentFilter.class), eq(null)))
        .thenReturn(new ApiModels.QueryResponse("Grounded response", List.of()));

    var result = service.query(new ApiModels.AgentQueryRequest("How does setup work?", null, null, null),
        WORKSPACE_ID);

    assertThat(result.answer()).isEqualTo("Grounded response");
    assertThat(result.steps()).extracting(ApiModels.AgentStepResponse::tool)
        .containsExactly("planner", "document-compare", "workspace-search", "workspace-search",
            "evidence-and-answer");
    assertThat(result.steps()).extracting(ApiModels.AgentStepResponse::status)
        .containsOnly("completed", "not-needed");
    verify(rag).queryWithSearchQueries(eq("How does setup work?"),
        eq(List.of("setup overview", "setup prerequisites")), eq(WORKSPACE_ID),
        any(DocumentStore.DocumentFilter.class), eq(null));
  }

  @Test
  void executesComparisonOnlyForTwoListedWorkspaceTextDocuments() {
    String firstSource = "uploads/" + WORKSPACE_ID + "/policy-v1.txt";
    String secondSource = "uploads/" + WORKSPACE_ID + "/policy-v2.pdf";
    when(documents.listDocuments(WORKSPACE_ID)).thenReturn(List.of(
        new DocumentStore.IndexedDocument("first", firstSource, 1, 50),
        new DocumentStore.IndexedDocument("second", secondSource, 1, 50)));
    when(planner.plan(eq("Compare the policies"), any())).thenReturn(
        new AgentPlanner.AgentPlan(List.of("policy changes"), "first", "second", "Compare versions"));
    when(rag.queryWithSearchQueries(any(), any(), eq(WORKSPACE_ID), any(), any()))
        .thenReturn(new ApiModels.QueryResponse("Summary", List.of()));
    ApiModels.DocumentCompareResponse diff = new ApiModels.DocumentCompareResponse(
        "policy-v1.txt", "policy-v2.pdf", List.of("old clause"), List.of("new clause"));
    when(comparison.compare(new ApiModels.DocumentCompareRequest(firstSource, secondSource), WORKSPACE_ID))
        .thenReturn(diff);

    var result = service.query(new ApiModels.AgentQueryRequest("Compare the policies", null, null, null),
        WORKSPACE_ID);

    assertThat(result.comparison()).isEqualTo(diff);
    assertThat(result.steps()).anySatisfy(step -> {
      assertThat(step.tool()).isEqualTo("document-compare");
      assertThat(step.status()).isEqualTo("completed");
    });
    verify(comparison).compare(new ApiModels.DocumentCompareRequest(firstSource, secondSource), WORKSPACE_ID);
  }

  @Test
  void rejectsPlansWithTooManySearchQueries() {
    when(documents.listDocuments(WORKSPACE_ID)).thenReturn(List.of());
    when(planner.plan(any(), any())).thenReturn(
        new AgentPlanner.AgentPlan(List.of("one", "two", "three", "four"), null, null, null));

    assertThatThrownBy(() -> service.query(
        new ApiModels.AgentQueryRequest("question", null, null, null), WORKSPACE_ID))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("invalid search plan");
  }

  @Test
  void rejectsComparisonWhenPlannerInventsAnUnknownDocumentIdBeforeSearching() {
    when(documents.listDocuments(WORKSPACE_ID)).thenReturn(List.of(
        new DocumentStore.IndexedDocument("known", "uploads/" + WORKSPACE_ID + "/known.txt", 1, 10)));
    when(planner.plan(any(), any())).thenReturn(
        new AgentPlanner.AgentPlan(List.of("question"), "known", "invented", "compare"));

    assertThatThrownBy(() -> service.query(
        new ApiModels.AgentQueryRequest("question", null, null, null), WORKSPACE_ID))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("invalid workspace document IDs");

    Mockito.verifyNoInteractions(rag, comparison);
  }

  @Test
  void rejectsIncompleteComparisonIdsBeforeSearching() {
    when(documents.listDocuments(WORKSPACE_ID)).thenReturn(List.of(
        new DocumentStore.IndexedDocument("known", "uploads/" + WORKSPACE_ID + "/known.txt", 1, 10)));
    when(planner.plan(any(), any())).thenReturn(
        new AgentPlanner.AgentPlan(List.of("question"), "known", null, null));

    assertThatThrownBy(() -> service.query(
        new ApiModels.AgentQueryRequest("question", null, null, null), WORKSPACE_ID))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("invalid workspace document IDs");

    Mockito.verifyNoInteractions(rag, comparison);
  }

  @Test
  void rejectsComparisonIdsOutsideTheBoundedPlannerDocumentList() {
    List<DocumentStore.IndexedDocument> indexedDocuments = java.util.stream.IntStream.range(0, 101)
        .mapToObj(index -> new DocumentStore.IndexedDocument(
            "doc-" + index, "uploads/" + WORKSPACE_ID + "/document-" + index + ".txt", 1, 10))
        .toList();
    when(documents.listDocuments(WORKSPACE_ID)).thenReturn(indexedDocuments);
    when(planner.plan(eq("question"), any())).thenReturn(
        new AgentPlanner.AgentPlan(List.of("question"), "doc-0", "doc-100", "compare"));

    assertThatThrownBy(() -> service.query(
        new ApiModels.AgentQueryRequest("question", null, null, null), WORKSPACE_ID))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("invalid workspace document IDs");

    Mockito.verifyNoInteractions(rag, comparison);
  }
}
