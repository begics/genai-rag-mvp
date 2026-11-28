package com.begics.rag.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OllamaStructuredChatModel implements StructuredChatModel {
  private static final int MAX_PLANNER_TOKENS = 512;

  private final RestClient restClient;
  private final String model;
  private final ObjectMapper objectMapper;

  OllamaStructuredChatModel(RestClient restClient, String model, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.model = model;
    this.objectMapper = objectMapper;
  }

  @Override
  public String answer(String question, String context) {
    return call(List.of(
        Map.of("role", "system", "content", question),
        Map.of("role", "user", "content", context)), null);
  }

  @Override
  public String answerWithSchema(String instructions, String context, String jsonSchema) {
    JsonNode schema;
    try {
      schema = objectMapper.readTree(jsonSchema);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("The local planner schema is invalid", exception);
    }
    return call(List.of(
        Map.of("role", "system", "content", instructions),
        Map.of("role", "user", "content", context)), schema);
  }

  private String call(List<Map<String, String>> messages, JsonNode schema) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("model", model);
    request.put("messages", messages);
    request.put("stream", false);
    request.put("options", Map.of("temperature", 0, "num_predict", MAX_PLANNER_TOKENS));
    if (schema != null) {
      request.put("format", schema);
    }
    try {
      String responseBody = restClient.post()
          .uri("/api/chat")
          .body(request)
          .retrieve()
          .body(String.class);
      if (responseBody == null) {
        return null;
      }
      JsonNode response;
      try {
        response = objectMapper.readTree(responseBody);
      } catch (JacksonException exception) {
        return responseBody;
      }
      JsonNode content = response == null ? null : response.path("message").path("content");
      return content != null && content.isTextual() ? content.stringValue() : null;
    } catch (RestClientException exception) {
      throw new AgentPlanner.PlannerUnavailableException(
          "Local Ollama agent planner request failed", exception);
    }
  }
}
