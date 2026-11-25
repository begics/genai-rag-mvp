package com.begics.rag.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnProperty(name = "rag.provider", havingValue = "offline", matchIfMissing = true)
public class DeterministicModels {
  @Bean
  EmbeddingModel embeddingModel(Environment environment) {
    int dimensions = EmbeddingDimensions.configured(environment);
    return text -> {
      float[] result = new float[dimensions];
      byte[] digest;
      try {
        digest = MessageDigest.getInstance("SHA-256").digest(text.toLowerCase().getBytes(StandardCharsets.UTF_8));
      } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException(exception);
      }
      for (int i = 0; i < result.length; i++) {
        result[i] = (digest[i % digest.length] & 0xff) / 255.0f;
      }
      return result;
    };
  }

  @Bean
  ChatModel chatModel() {
    return (question, context) -> context.isBlank()
        ? "I could not find an answer in the indexed sources."
        : "Based on the indexed sources: " + context.lines().findFirst().orElse(context);
  }

  @Bean
  Reranker reranker() {
    return (query, candidates, limit) -> candidates.stream().limit(limit).toList();
  }
}
