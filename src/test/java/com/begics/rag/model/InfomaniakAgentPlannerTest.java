package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
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
}
