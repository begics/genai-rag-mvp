package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PipelineInfoServiceTest {
  private static final String DEFAULT_PLANNER_MODEL = "google/gemma-4-31B-it";

  @Test
  void reportsTheDefaultSmallPlannerAndKeepsTheAnswerModelVisible() {
    var environment = new MockEnvironment()
        .withProperty("rag.provider", "infomaniak")
        .withProperty("rag.infomaniak.chat-model", "google/gemma-4-31B-it");

    var result = new PipelineInfoService(environment).current();

    assertThat(result.chatModel()).isEqualTo("google/gemma-4-31B-it");
    assertThat(result.agentPlanner()).contains(DEFAULT_PLANNER_MODEL);
    assertThat(result.agentPlanner()).contains("configured, not live health-checked");
  }

  @Test
  void reportsAPlannerModelOverrideSeparatelyFromTheAnswerModel() {
    var environment = new MockEnvironment()
        .withProperty("rag.provider", "infomaniak")
        .withProperty("rag.infomaniak.chat-model", "answer-model")
        .withProperty("rag.infomaniak.agent-planner-model", "planner-override");

    var result = new PipelineInfoService(environment).current();

    assertThat(result.chatModel()).isEqualTo("answer-model");
    assertThat(result.agentPlanner()).contains("planner-override");
    assertThat(result.agentPlanner()).doesNotContain(DEFAULT_PLANNER_MODEL);
  }

  @Test
  void reportsAnEmptyPlannerOverrideAsTheDefaultModel() {
    var environment = new MockEnvironment()
        .withProperty("rag.provider", "infomaniak")
        .withProperty("rag.infomaniak.agent-planner-model", "");

    var result = new PipelineInfoService(environment).current();

    assertThat(result.agentPlanner()).contains(DEFAULT_PLANNER_MODEL);
  }

  @Test
  void keepsOfflineModeDeterministicAndDoesNotAdvertiseTheInfomaniakPlanner() {
    var environment = new MockEnvironment().withProperty("rag.provider", "offline");

    var result = new PipelineInfoService(environment).current();

    assertThat(result.agentPlanner()).contains("Deterministic local single-query planner");
    assertThat(result.agentPlanner()).doesNotContain(DEFAULT_PLANNER_MODEL);
  }

  @Test
  void reportsTheOptionalOfflineOllamaPlannerSeparatelyFromTheOfflineChatModel() {
    var environment = new MockEnvironment()
        .withProperty("rag.provider", "offline")
        .withProperty("rag.offline-agent-planner.provider", "ollama")
        .withProperty("rag.offline-agent-planner.ollama.model", "gemma4:31b");

    var result = new PipelineInfoService(environment).current();

    assertThat(result.agentPlanner()).contains("Local Ollama planner · gemma4:31b");
    assertThat(result.chatModel()).isEqualTo("canned demo response");
  }
}
