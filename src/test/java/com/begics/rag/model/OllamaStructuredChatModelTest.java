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
            org.hamcrest.Matchers.containsString("\"temperature\":0"),
            org.hamcrest.Matchers.containsString("\"num_predict\":512"),
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
        .hasMessage("Local Ollama agent planner request failed");
    server.verify();
  }
}
