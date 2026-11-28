package com.begics.rag.model;

import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.domain.TranscriptionFormats;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnProperty(name = "rag.provider", havingValue = "ollama")
class OllamaAiModels {
  @Bean
  RestClient ollamaRestClient(Environment environment) {
    String baseUrl = environment.getProperty("rag.ollama.base-url", "http://host.docker.internal:11434");
    int timeoutMs = environment.getProperty("rag.ollama.request-timeout-ms", Integer.class, 180_000);
    validateSettings(baseUrl, timeoutMs);
    Duration timeout = Duration.ofMillis(timeoutMs);
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(timeout);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }

  @Bean
  StructuredChatModel ollamaStructuredChatModel(
      RestClient ollamaRestClient, Environment environment, ObjectMapper objectMapper) {
    String model = environment.getProperty("rag.ollama.chat-model", "gemma4:12b-mlx");
    if (model.isBlank()) {
      throw new IllegalStateException("RAG_OLLAMA_CHAT_MODEL must not be blank");
    }
    int contextTokens = contextTokens(environment);
    return new OllamaStructuredChatModel(ollamaRestClient, model, objectMapper, 2_048, contextTokens);
  }

  @Bean
  EmbeddingModel ollamaEmbeddingModel(
      RestClient ollamaRestClient, Environment environment, ObjectMapper objectMapper) {
    String model = environment.getProperty("rag.ollama.embedding-model", "embeddinggemma-2");
    int dimensions = environment.getProperty("rag.ollama.embedding-dimensions", Integer.class, 768);
    if (model.isBlank()) {
      throw new IllegalStateException("RAG_OLLAMA_EMBEDDING_MODEL must not be blank");
    }
    if (dimensions < 1 || dimensions > 4000) {
      throw new IllegalStateException("RAG_EMBEDDING_DIMENSIONS must be between 1 and 4000");
    }
    return new OllamaEmbeddingModel(ollamaRestClient, model, dimensions, objectMapper);
  }

  @Bean
  AgentPlanner ollamaAgentPlanner(
      RestClient ollamaRestClient, Environment environment, ObjectMapper objectMapper) {
    String model = environment.getProperty("rag.ollama.chat-model", "gemma4:12b-mlx");
    int contextTokens = contextTokens(environment);
    return new StructuredAgentPlanner(
        new OllamaStructuredChatModel(ollamaRestClient, model, objectMapper, 512, contextTokens), objectMapper);
  }

  private static int contextTokens(Environment environment) {
    int contextTokens = environment.getProperty(
        "rag.ollama.context-tokens", Integer.class,
        OllamaStructuredChatModel.DEFAULT_CONTEXT_TOKENS);
    if (contextTokens < 1_024 || contextTokens > OllamaStructuredChatModel.MAX_CONTEXT_TOKENS) {
      throw new IllegalStateException(
          "RAG_OLLAMA_CONTEXT_TOKENS must be between 1024 and "
              + OllamaStructuredChatModel.MAX_CONTEXT_TOKENS);
    }
    return contextTokens;
  }

  @Bean
  @ConditionalOnProperty(
      name = "rag.ollama.rerank-provider", havingValue = "ollama")
  Reranker ollamaReranker(RestClient ollamaRestClient, Environment environment, ObjectMapper objectMapper) {
    String model = environment.getProperty(
        "rag.ollama.rerank-model", "AuditAid/Qwen3_Reranker:0.6B_Q8");
    if (model.isBlank()) {
      throw new IllegalStateException("RAG_OLLAMA_RERANK_MODEL must not be blank");
    }
    return new OllamaReranker(ollamaRestClient, model, objectMapper);
  }

  @Bean
  @ConditionalOnProperty(name = "rag.ollama.rerank-provider", havingValue = "infomaniak")
  Reranker infomaniakRerankerForOllama(
      Environment environment, ObjectProvider<MeterRegistry> meterRegistry) {
    int productId = environment.getProperty("rag.infomaniak.product-id", Integer.class, 0);
    String apiKey = environment.getProperty("rag.infomaniak.api-key", "");
    String model = environment.getProperty("rag.infomaniak.rerank-model", "");
    int connectTimeoutMs = environment.getProperty(
        "rag.infomaniak.connect-timeout-ms", Integer.class, 10_000);
    int readTimeoutMs = environment.getProperty(
        "rag.infomaniak.read-timeout-ms", Integer.class, 60_000);
    int maxConcurrentCalls = environment.getProperty(
        "rag.provider-limits.max-concurrent-calls", Integer.class, 8);
    if (productId < 1) {
      throw new IllegalStateException("INFOMANIAK_AI_PRODUCT_ID must be a positive integer");
    }
    if (apiKey.isBlank()) {
      throw new IllegalStateException("INFOMANIAK_AI_API_KEY must be configured for Infomaniak reranking");
    }
    if (model.isBlank()) {
      throw new IllegalStateException("INFOMANIAK_AI_RERANK_MODEL must be configured for Infomaniak reranking");
    }
    if (connectTimeoutMs < 1 || readTimeoutMs < 1) {
      throw new IllegalStateException("Infomaniak connect and read timeouts must be positive");
    }

    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
    RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
    ProviderCallLimiter limiter = new ProviderCallLimiter(
        meterRegistry.getIfAvailable(SimpleMeterRegistry::new), maxConcurrentCalls);
    return new InfomaniakReranker(
        builder,
        "https://api.infomaniak.com/2/ai/" + productId + "/cohere/v2/rerank",
        apiKey,
        model,
        new ProviderTextRedactor(),
        limiter);
  }

  @Bean
  GroundingVerifier ollamaGroundingVerifier(StructuredChatModel chat, ObjectMapper objectMapper) {
    return new OllamaGroundingVerifier(chat, objectMapper);
  }

  @Bean
  DocumentRiskScreening ollamaDocumentRiskScreening(
      StructuredChatModel chat, ObjectMapper objectMapper) {
    return new InfomaniakDocumentRiskScreening(chat, objectMapper, new ProviderTextRedactor());
  }

  @Bean
  InvoiceExtractor ollamaInvoiceExtractor(StructuredChatModel chat, ObjectMapper objectMapper) {
    return new InfomaniakInvoiceExtractor(chat, objectMapper);
  }

  @Bean
  SpeechTranscription ollamaSpeechTranscription() {
    return new SpeechTranscription() {
      @Override
      public boolean available() {
        return false;
      }

      @Override
      public boolean supports(String filename) {
        return TranscriptionFormats.supports(filename);
      }

      @Override
      public String transcribe(String filename, byte[] content) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Local Ollama speech transcription is not configured");
      }
    };
  }

  private static void validateSettings(String baseUrl, int timeoutMs) {
    if (timeoutMs < 1) {
      throw new IllegalStateException("RAG_OLLAMA_REQUEST_TIMEOUT_MS must be positive");
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
          "RAG_OLLAMA_BASE_URL must be an HTTP(S) URL without credentials or query parameters", exception);
    }
  }
}
