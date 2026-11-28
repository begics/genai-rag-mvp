package com.begics.rag.model;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.api.AgentController;
import com.begics.rag.service.AgentService;
import com.begics.rag.service.DocumentCompareService;
import com.begics.rag.service.RagService;
import com.begics.rag.service.WorkspaceAccessService;
import com.begics.rag.store.DocumentStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class AgentPlanningFlowTest {
  private static final UUID WORKSPACE_ID =
      UUID.fromString("ff24fa12-e7d3-4e98-8f40-9c6e7c090cd1");
  private static final String WORKSPACE_HEADER = WORKSPACE_ID.toString();
  private static final String SOURCE = "uploads/" + WORKSPACE_HEADER + "/manual.md";

  private final StructuredChatModel plannerChat = Mockito.mock(StructuredChatModel.class);
  private final DocumentStore documents = Mockito.mock(DocumentStore.class);
  private final RagService rag = Mockito.mock(RagService.class);
  private final DocumentCompareService comparison = Mockito.mock(DocumentCompareService.class);
  private final WorkspaceAccessService workspaces = Mockito.mock(WorkspaceAccessService.class);
  private final AgentPlanner planner =
      new StructuredAgentPlanner(plannerChat, JsonMapper.builder().build());
  private final AgentService agent = new AgentService(planner, documents, rag, comparison);
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(agent, workspaces))
      .setControllerAdvice(new ValidationAdvice())
      .build();

  @BeforeEach
  void setUpWorkspace() {
    when(workspaces.resolve(any(), eq(WORKSPACE_HEADER))).thenReturn(WORKSPACE_ID);
    when(documents.listDocuments(WORKSPACE_ID)).thenReturn(List.of(
        new DocumentStore.IndexedDocument("manual-1", SOURCE, 2, 128)));
  }

  @Test
  void plansSearchesAndReturnsAnswerSourcesAndVisibleToolTrace() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenReturn(plan("""
            "searchQueries":["setup overview","setup prerequisites"],
            "firstDocumentId":null,"secondDocumentId":null,"comparisonRationale":null
            """));
    var source = new ApiModels.SourceReference("manual-1", "manual.md", 1, "Install the package.");
    when(rag.queryWithSearchQueries(eq("How do I set this up?"),
        eq(List.of("setup overview", "setup prerequisites")), eq(WORKSPACE_ID),
        any(DocumentStore.DocumentFilter.class), eq(null)))
        .thenReturn(new ApiModels.QueryResponse("Install the package first.", List.of(source)));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"How do I set this up?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("Install the package first."))
        .andExpect(jsonPath("$.sources[0].documentId").value("manual-1"))
        .andExpect(jsonPath("$.steps[0].tool").value("planner"))
        .andExpect(jsonPath("$.steps[1].tool").value("document-compare"))
        .andExpect(jsonPath("$.steps[1].status").value("not-needed"))
        .andExpect(jsonPath("$.steps[2].tool").value("workspace-search"))
        .andExpect(jsonPath("$.steps[3].tool").value("workspace-search"))
        .andExpect(jsonPath("$.steps[4].tool").value("evidence-and-answer"))
        .andExpect(jsonPath("$.maxSteps").value(6));

    verify(plannerChat).answerWithSchema(anyString(),
        org.mockito.ArgumentMatchers.argThat(input ->
            input.contains("\"question\":\"How do I set this up?\"")
                && input.contains("\"id\":\"manual-1\"")
                && input.contains("manual.md")),
        anyString());
    verify(rag).queryWithSearchQueries(eq("How do I set this up?"),
        eq(List.of("setup overview", "setup prerequisites")), eq(WORKSPACE_ID),
        any(DocumentStore.DocumentFilter.class), eq(null));
  }

  @Test
  void sendsQuestionFiltersAndAnswerModeThroughTheAgentFlow() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenReturn(plan("""
            "searchQueries":["retention policy"],
            "firstDocumentId":null,"secondDocumentId":null,"comparisonRationale":null
            """));
    when(rag.queryWithSearchQueries(eq("How long is data retained?"), eq(List.of("retention policy")),
        eq(WORKSPACE_ID), eq(new DocumentStore.DocumentFilter("manual.md", "policy")), eq("support")))
        .thenReturn(new ApiModels.QueryResponse("The policy says 30 days.", List.of()));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"question":"How long is data retained?","source":"manual.md",
                 "documentType":"policy","assistantMode":"support"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("The policy says 30 days."));

    verify(rag).queryWithSearchQueries(eq("How long is data retained?"), eq(List.of("retention policy")),
        eq(WORKSPACE_ID), eq(new DocumentStore.DocumentFilter("manual.md", "policy")), eq("support"));
  }

  @Test
  void comparesOnlyTheTwoWorkspaceDocumentsSelectedByThePlanner() throws Exception {
    String firstSource = "uploads/" + WORKSPACE_HEADER + "/policy-2025.txt";
    String secondSource = "uploads/" + WORKSPACE_HEADER + "/policy-2026.pdf";
    when(documents.listDocuments(WORKSPACE_ID)).thenReturn(List.of(
        new DocumentStore.IndexedDocument("policy-2025", firstSource, 1, 50),
        new DocumentStore.IndexedDocument("policy-2026", secondSource, 1, 50)));
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenReturn(plan("""
            "searchQueries":["policy changes"],
            "firstDocumentId":"policy-2025","secondDocumentId":"policy-2026",
            "comparisonRationale":"The request asks to compare both versions"
            """));
    when(comparison.compare(new ApiModels.DocumentCompareRequest(firstSource, secondSource), WORKSPACE_ID))
        .thenReturn(new ApiModels.DocumentCompareResponse(
            "policy-2025.txt", "policy-2026.pdf", List.of("Old limit"), List.of("New limit")));
    when(rag.queryWithSearchQueries(anyString(), any(), eq(WORKSPACE_ID), any(), any()))
        .thenReturn(new ApiModels.QueryResponse("The limit changed.", List.of()));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Compare these policies\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comparison.firstFilename").value("policy-2025.txt"))
        .andExpect(jsonPath("$.comparison.removedParagraphs[0]").value("Old limit"))
        .andExpect(jsonPath("$.comparison.addedParagraphs[0]").value("New limit"))
        .andExpect(jsonPath("$.steps[1].status").value("completed"));

    verify(comparison).compare(new ApiModels.DocumentCompareRequest(firstSource, secondSource), WORKSPACE_ID);
  }

  @Test
  void rejectsUnknownComparisonDocumentIdsBeforeCallingTools() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenReturn(plan("""
            "searchQueries":["policy"],
            "firstDocumentId":"manual-1","secondDocumentId":"not-in-workspace",
            "comparisonRationale":"Compare documents"
            """));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Compare documents\"}"))
        .andExpect(status().isBadGateway());

    verify(rag).validateQuestion("Compare documents");
    Mockito.verifyNoMoreInteractions(rag);
    verifyNoInteractions(comparison);
  }

  @Test
  void deduplicatesRepeatedPlannerSearchesBeforeCallingRetrieval() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenReturn(plan("""
            "searchQueries":["exact match","exact match","related topic"],
            "firstDocumentId":null,"secondDocumentId":null,"comparisonRationale":null
            """));
    when(rag.queryWithSearchQueries(anyString(), eq(List.of("exact match", "related topic")),
        eq(WORKSPACE_ID), any(), any()))
        .thenReturn(new ApiModels.QueryResponse("Answer", List.of()));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Find this\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.steps[2].tool").value("workspace-search"))
        .andExpect(jsonPath("$.steps[3].tool").value("workspace-search"))
        .andExpect(jsonPath("$.steps[4].tool").value("evidence-and-answer"));

    verify(rag).queryWithSearchQueries(anyString(), eq(List.of("exact match", "related topic")),
        eq(WORKSPACE_ID), any(), any());
  }

  @Test
  void rejectsPlansExceedingTheSearchBoundWithoutRunningRetrieval() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenReturn(plan("""
            "searchQueries":["one","two","three","four"],
            "firstDocumentId":null,"secondDocumentId":null,"comparisonRationale":null
            """));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Find something\"}"))
        .andExpect(status().isBadGateway());

    verify(rag).validateQuestion("Find something");
    Mockito.verifyNoMoreInteractions(rag);
    verifyNoInteractions(comparison);
  }

  @Test
  void rejectsBlankPlannerQueriesWithoutRunningRetrieval() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenReturn(plan("""
            "searchQueries":["  "],
            "firstDocumentId":null,"secondDocumentId":null,"comparisonRationale":null
            """));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Find something\"}"))
        .andExpect(status().isBadGateway());

    verify(rag).validateQuestion("Find something");
    Mockito.verifyNoMoreInteractions(rag);
    verifyNoInteractions(comparison);
  }

  @Test
  void rejectsUnknownPlannerJsonFieldsAsBadGatewayWithoutRunningTools() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenReturn("""
            {"searchQueries":["one"],"firstDocumentId":null,"secondDocumentId":null,
             "comparisonRationale":null,"unexpected":"value"}
            """);

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Find something\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("Agent planner returned invalid structured output"));

    verify(rag).validateQuestion("Find something");
    Mockito.verifyNoMoreInteractions(rag);
    verifyNoInteractions(comparison);
  }

  @Test
  void rejectsBlankAndOversizedAgentQuestionsAtTheHttpBoundary() throws Exception {
    mvc.perform(post("/api/agent/query").contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/agent/query").contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"" + "x".repeat(10_001) + "\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(plannerChat, documents, rag, comparison);
  }

  @Test
  void surfacesAnUnavailablePlannerInsteadOfSilentlyCallingAnotherModel() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("planner provider unavailable"));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Find something\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("planner unavailable"));

    verify(rag).validateQuestion("Find something");
    Mockito.verifyNoMoreInteractions(rag);
    verifyNoInteractions(comparison);
  }

  @Test
  void reportsAnUnavailableLocalPlannerAsServiceUnavailable() throws Exception {
    when(plannerChat.answerWithSchema(anyString(), anyString(), anyString()))
        .thenThrow(new AgentPlanner.PlannerUnavailableException("local planner unavailable",
            new IllegalStateException("connection refused")));

    mvc.perform(post("/api/agent/query")
            .header("X-Workspace-ID", WORKSPACE_HEADER)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Find something\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("Agent planner is unavailable"));

    verify(rag).validateQuestion("Find something");
    Mockito.verifyNoMoreInteractions(rag);
    verifyNoInteractions(comparison);
  }

  private static String plan(String fields) {
    return "{%s}".formatted(fields);
  }

  @RestControllerAdvice
  static class ValidationAdvice {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidRequest() {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException exception) {
      return ResponseEntity.status(exception.getStatusCode())
          .body(Map.of("error", exception.getReason() == null ? "request failed" : exception.getReason()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> plannerFailure() {
      return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "planner unavailable"));
    }
  }
}
