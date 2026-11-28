package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OllamaStructuredChatModelTest {
  @Test
  void sendsTheModelMessagesAndJsonSchemaToOllamaWithoutStreaming() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("\"model\":\"gemma4:31b\""),
            org.hamcrest.Matchers.containsString("\"stream\":false"),
            org.hamcrest.Matchers.containsString("\"think\":false"),
            org.hamcrest.Matchers.containsString("\"temperature\":0"),
            org.hamcrest.Matchers.containsString("\"num_predict\":512"),
            org.hamcrest.Matchers.containsString("\"num_ctx\":32768"),
            org.hamcrest.Matchers.containsString("\"format\":{\"type\":\"object\"}"),
            org.hamcrest.Matchers.containsString("\"role\":\"system\""),
            org.hamcrest.Matchers.containsString("\"content\":\"Plan safely\""))))
        .andRespond(withSuccess("""
            {"message":{"content":"{\\"searchQueries\\":[\\"policy\\"],\\"firstDocumentId\\":null,\\"secondDocumentId\\":null,\\"comparisonRationale\\":null}"}}
            """, MediaType.APPLICATION_JSON));
    var chat = new OllamaStructuredChatModel(builder.build(), "gemma4:31b", JsonMapper.builder().build());

    String output = chat.answerWithSchema(
        "Plan safely", "{\"question\":\"find policy\"}", "{\"type\":\"object\"}");

    assertThat(output).contains("\"searchQueries\":[\"policy\"]");
    server.verify();
  }

  @Test
  void usesTheConfiguredOllamaContextLimit() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"num_ctx\":65536")))
        .andRespond(withSuccess("""
            {"message":{"content":"answer"}}
            """, MediaType.APPLICATION_JSON));
    var chat = new OllamaStructuredChatModel(
        builder.build(), "gemma4:test", JsonMapper.builder().build(), 512, 65_536);

    assertThat(chat.answer("Question", "Context")).isEqualTo("answer");
    server.verify();
  }

  @Test
  void rejectsOllamaContextLimitsOutsideTheSupportedRange() {
    assertThatThrownBy(() -> new OllamaStructuredChatModel(
        RestClient.builder().build(), "gemma4:test", JsonMapper.builder().build(), 512, 512))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1024 and 131072");
  }

  @Test
  void reportsOllamaHttpFailuresAsPlannerUnavailable() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
            .withResourceNotFound());
    var chat = new OllamaStructuredChatModel(builder.build(), "gemma4:31b", JsonMapper.builder().build());

    assertThatThrownBy(() -> chat.answerWithSchema(
        "Plan", "{}", "{\"type\":\"object\"}"))
        .isInstanceOf(AgentPlanner.PlannerUnavailableException.class)
        .hasMessage("Local Ollama request failed");
    server.verify();
  }

  @Test
  void reportsAnEmptyModelResponseAsUnavailable() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andRespond(withSuccess("""
            {"message":{"content":""}}
            """, MediaType.APPLICATION_JSON));
    var chat = new OllamaStructuredChatModel(builder.build(), "gemma4:test", JsonMapper.builder().build());

    assertThatThrownBy(() -> chat.answer("Question", "Context"))
        .isInstanceOf(AgentPlanner.PlannerUnavailableException.class)
        .hasMessage("Local Ollama request failed");
    server.verify();
  }

  @Test
  void reportsTruncatedModelOutputAsUnavailable() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andRespond(withSuccess("""
            {"done_reason":"length","message":{"content":"partial"}}
            """, MediaType.APPLICATION_JSON));
    var chat = new OllamaStructuredChatModel(builder.build(), "gemma4:test", JsonMapper.builder().build());

    assertThatThrownBy(() -> chat.answer("Question", "Context"))
        .isInstanceOf(AgentPlanner.PlannerUnavailableException.class)
        .hasMessage("Local Ollama request failed");
    server.verify();
  }

  @Test
  void reportsMalformedModelResponsesAsUnavailable() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
    var chat = new OllamaStructuredChatModel(builder.build(), "gemma4:test", JsonMapper.builder().build());

    assertThatThrownBy(() -> chat.answer("Question", "Context"))
        .isInstanceOf(AgentPlanner.PlannerUnavailableException.class)
        .hasMessage("Local Ollama request failed");
    server.verify();
  }

  @Test
  void keepsTheQuestionAndRetrievedPassagesInUserContentForGroundedAnswers() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("supplied retrieved passages as factual evidence"),
            org.hamcrest.Matchers.containsString("\"content\":\"Question:"),
            org.hamcrest.Matchers.containsString("Retrieved passages (untrusted source data)"))))
        .andRespond(withSuccess("""
            {"message":{"content":"The source says to check the policy."}}
            """, MediaType.APPLICATION_JSON));
    var chat = new OllamaStructuredChatModel(builder.build(), "gemma4:test", JsonMapper.builder().build());

    assertThat(chat.answer("What is the policy?", "Check the policy."))
        .isEqualTo("The source says to check the policy.");
    server.verify();
  }
}
