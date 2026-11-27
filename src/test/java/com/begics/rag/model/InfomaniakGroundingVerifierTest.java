package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class InfomaniakGroundingVerifierTest {
  private final tools.jackson.databind.ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  void acceptsOnlyAValidStructuredGroundingDecision() {
    InfomaniakGroundingVerifier verifier = new InfomaniakGroundingVerifier(
        new StructuredChatModel() {
          @Override
          public String answer(String question, String context) {
            throw new AssertionError("The grounding verifier must use structured output");
          }

          @Override
          public String answerWithSchema(String instructions, String context, String schema) {
            assertThat(instructions).contains("every material factual claim");
            assertThat(context).contains("The answer is supported", "The source passage");
            assertThat(schema).contains("\"grounded\":{\"type\":\"boolean\"}");
            return "{\"grounded\":true}";
          }
        },
        objectMapper);

    assertThat(verifier.isGrounded(
        "Question", "The answer is supported", "The source passage")).isTrue();
  }

  @Test
  void rejectsMalformedDuplicateAndTrailingVerifierOutput() {
    for (String output : new String[] {
        "{\"grounded\":\"yes\"}",
        "{\"grounded\":true,\"grounded\":false}",
        "{\"grounded\":true} trailing"
    }) {
      InfomaniakGroundingVerifier verifier = new InfomaniakGroundingVerifier(
          chatReturning(output), objectMapper);
      assertThatThrownBy(() -> verifier.isGrounded("question", "answer", "passages"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid structured output");
    }
  }

  @Test
  void failsClosedWhenTheVerifierDoesNotConfirmSupport() {
    InfomaniakGroundingVerifier verifier = new InfomaniakGroundingVerifier(
        chatReturning("{\"grounded\":false}"), objectMapper);

    assertThat(verifier.isGrounded("question", "unsupported answer", "passages")).isFalse();
  }

  private static StructuredChatModel chatReturning(String output) {
    return new StructuredChatModel() {
      @Override
      public String answer(String question, String context) {
        throw new AssertionError("The grounding verifier must use structured output");
      }

      @Override
      public String answerWithSchema(String instructions, String context, String schema) {
        return output;
      }
    };
  }
}
