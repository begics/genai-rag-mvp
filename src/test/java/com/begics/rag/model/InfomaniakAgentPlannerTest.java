package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

class InfomaniakAgentPlannerTest {
  @Test
  void requestsBoundedStrictlyStructuredPlansWithOnlyWorkspaceDocuments() {
    StructuredChatModel chat = Mockito.mock(StructuredChatModel.class);
    String output = """
        {"searchQueries":["employee leave policy","leave approval steps"],
         "firstDocumentId":"doc-1","secondDocumentId":"doc-2",
         "comparisonRationale":"The user requested a policy comparison"}
        """;
    when(chat.answerWithSchema(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(output);
    InfomaniakAgentPlanner planner = new InfomaniakAgentPlanner(chat, JsonMapper.builder().build());

    AgentPlanner.AgentPlan plan = planner.plan("Compare employee leave policies", List.of(
        new AgentPlanner.AgentDocument("doc-1", "leave-2025.pdf", "pdf"),
        new AgentPlanner.AgentDocument("doc-2", "leave-2026.pdf", "pdf")));

    assertThat(plan.searchQueries()).containsExactly("employee leave policy", "leave approval steps");
    assertThat(plan.firstDocumentId()).isEqualTo("doc-1");
    verify(chat).answerWithSchema(contains("read-only agentic RAG run"),
        argThat(input -> input.contains("\"question\":\"Compare employee leave policies\"")
            && input.contains("\"id\":\"doc-1\"") && input.contains("leave-2025.pdf")),
        argThat(schema -> schema.contains("\"maxItems\":3")
            && schema.contains("\"additionalProperties\":false")));
  }

  @Test
  void parsesAOneQueryPlanWithoutRequestingDocumentComparison() {
    StructuredChatModel chat = Mockito.mock(StructuredChatModel.class);
    when(chat.answerWithSchema(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn("""
            {"searchQueries":["Wo steht die Kündigungsfrist?"],
             "firstDocumentId":null,"secondDocumentId":null,"comparisonRationale":null}
            """);

    AgentPlanner.AgentPlan plan =
        new InfomaniakAgentPlanner(chat, JsonMapper.builder().build()).plan("Kündigungsfrist?", List.of());

    assertThat(plan.searchQueries()).containsExactly("Wo steht die Kündigungsfrist?");
    assertThat(plan.firstDocumentId()).isNull();
    assertThat(plan.secondDocumentId()).isNull();
  }

  @Test
  void passesNoDocumentsWhenTheWorkspaceIndexIsEmpty() {
    StructuredChatModel chat = Mockito.mock(StructuredChatModel.class);
    when(chat.answerWithSchema(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn("""
            {"searchQueries":["missing information"],"firstDocumentId":null,
             "secondDocumentId":null,"comparisonRationale":null}
            """);
    AgentPlanner planner = new InfomaniakAgentPlanner(chat, JsonMapper.builder().build());

    planner.plan("What is not in my files?", List.of());

    verify(chat).answerWithSchema(Mockito.anyString(),
        argThat(input -> input.contains("\"workspaceDocuments\":[]")),
        Mockito.anyString());
  }

  @Test
  void rejectsMalformedStructuredPlannerOutput() {
    StructuredChatModel chat = Mockito.mock(StructuredChatModel.class);
    when(chat.answerWithSchema(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn("not JSON");

    assertThatThrownBy(() -> new InfomaniakAgentPlanner(chat, JsonMapper.builder().build())
        .plan("question", List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid structured output");
  }

  @Test
  void doesNotHidePlannerProviderFailures() {
    StructuredChatModel chat = Mockito.mock(StructuredChatModel.class);
    when(chat.answerWithSchema(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
        .thenThrow(new IllegalStateException("provider unavailable"));

    assertThatThrownBy(() -> new InfomaniakAgentPlanner(chat, JsonMapper.builder().build())
        .plan("question", List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("provider unavailable");
  }
}
