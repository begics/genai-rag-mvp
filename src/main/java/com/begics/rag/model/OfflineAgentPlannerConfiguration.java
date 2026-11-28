package com.begics.rag.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(name = "rag.provider", havingValue = "offline", matchIfMissing = true)
class OfflineAgentPlannerConfiguration {
  @Bean
  @ConditionalOnProperty(
      name = "rag.offline-agent-planner.provider", havingValue = "deterministic", matchIfMissing = true)
  AgentPlanner deterministicAgentPlanner() {
    return (question, documents) -> {
      String query = question.strip();
      if (query.length() > 500) {
        int boundary = query.lastIndexOf(' ', 500);
        query = query.substring(0, boundary > 0 ? boundary : 500);
      }
      return new AgentPlanner.AgentPlan(List.of(query), null, null, null);
    };
  }

  @Bean
  @ConditionalOnProperty(name = "rag.offline-agent-planner.provider", havingValue = "ollama")
  AgentPlanner ollamaAgentPlanner(RestClient.Builder builder, Environment environment, ObjectMapper objectMapper) {
    String baseUrl = environment.getProperty(
        "rag.offline-agent-planner.ollama.base-url", "http://host.docker.internal:11434");
    String model = environment.getProperty(
        "rag.offline-agent-planner.ollama.model", "gemma4:31b");
    int timeoutMs = environment.getProperty(
        "rag.offline-agent-planner.ollama.timeout-ms", Integer.class, 180_000);
    validateOllamaSettings(baseUrl, model, timeoutMs);

    Duration timeout = Duration.ofMillis(timeoutMs);
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(timeout);
    RestClient ollamaClient = builder.clone()
        .baseUrl(baseUrl)
        .requestFactory(requestFactory)
        .build();
    StructuredChatModel localChat = new OllamaStructuredChatModel(ollamaClient, model, objectMapper);
    return new StructuredAgentPlanner(localChat, objectMapper);
  }

  private static void validateOllamaSettings(String baseUrl, String model, int timeoutMs) {
    if (model == null || model.isBlank()) {
      throw new IllegalStateException("RAG_OFFLINE_AGENT_PLANNER_MODEL must not be blank");
    }
    if (timeoutMs < 1) {
      throw new IllegalStateException("RAG_OFFLINE_AGENT_PLANNER_TIMEOUT_MS must be positive");
    }
    try {
      URI uri = URI.create(baseUrl);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "RAG_OFFLINE_AGENT_PLANNER_BASE_URL must be an HTTP(S) URL without credentials or query parameters",
          exception);
    }
  }
}
