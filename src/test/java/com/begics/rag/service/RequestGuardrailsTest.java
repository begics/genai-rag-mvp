package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RequestGuardrailsTest {
  @Test
  void rejectsConfiguredRestrictedTopicsCaseInsensitively() {
    RequestGuardrails guardrails = new RequestGuardrails("restricted marker,internal code");

    assertThatThrownBy(() -> guardrails.validateQuestion("Show the RESTRICTED MARKER please"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("configured restricted topic");
    assertThatThrownBy(() -> guardrails.validateQuestion("What is the internal code?"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void leavesQuestionsUnrestrictedWhenNoPolicyIsConfigured() {
    RequestGuardrails guardrails = new RequestGuardrails("");

    guardrails.validateQuestion("ordinary question");
    assertThat(guardrails).isNotNull();
  }
}
