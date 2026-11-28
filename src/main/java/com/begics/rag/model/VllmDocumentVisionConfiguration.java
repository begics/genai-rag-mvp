package com.begics.rag.model;

import com.begics.rag.domain.DocumentVision;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(name = "rag.document-vision.provider", havingValue = "vllm")
class VllmDocumentVisionConfiguration {
  @Bean
  DocumentVision vllmDocumentVision(Environment environment, ObjectMapper objectMapper) {
    String baseUrl = environment.getProperty(
        "rag.document-vision.base-url", "http://host.docker.internal:8000");
    int timeoutMs = environment.getProperty(
        "rag.document-vision.timeout-ms", Integer.class, 180_000);
    String model = environment.getProperty("rag.document-vision.model", "");
    String reviewModel = environment.getProperty(
        "rag.document-vision.review-model", model);
    if (reviewModel.isBlank()) {
      reviewModel = model;
    }
    if (model.isBlank() || reviewModel.isBlank() || timeoutMs < 1) {
      throw new IllegalStateException("Local vLLM model names and timeout must be configured");
    }
    validateBaseUrl(baseUrl);
    Duration timeout = Duration.ofMillis(timeoutMs);
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(timeout);
    return new LocalDocumentVision(
        RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build(),
        LocalDocumentVision.Protocol.OPENAI_COMPATIBLE,
        model, reviewModel, objectMapper,
        environment.getProperty("rag.ollama.context-tokens", Integer.class, 32_768));
  }

  private static void validateBaseUrl(String baseUrl) {
    try {
      URI uri = URI.create(baseUrl);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getQuery() != null || uri.getFragment() != null || !isLocalHost(uri.getHost())) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "RAG_DOCUMENT_VISION_BASE_URL must be an HTTP(S) URL without credentials or query parameters",
          exception);
    }
  }

  private static boolean isLocalHost(String host) {
    String normalized = host.toLowerCase(java.util.Locale.ROOT);
    return normalized.equals("localhost") || normalized.equals("host.docker.internal")
        || normalized.equals("127.0.0.1") || normalized.equals("::1") || normalized.equals("[::1]");
  }
}
