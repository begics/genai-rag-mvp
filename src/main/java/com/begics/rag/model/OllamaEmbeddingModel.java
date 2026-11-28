package com.begics.rag.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OllamaEmbeddingModel implements EmbeddingModel {
  private static final int MAX_BATCH_SIZE = 8;

  private final RestClient restClient;
  private final String model;
  private final int dimensions;
  private final ObjectMapper objectMapper;

  OllamaEmbeddingModel(
      RestClient restClient, String model, int dimensions, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.model = model;
    this.dimensions = dimensions;
    this.objectMapper = objectMapper;
  }

  @Override
  public float[] embed(String text) {
    return embedAll(List.of(text)).getFirst();
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    if (texts.isEmpty()) {
      return List.of();
    }
    List<float[]> embeddings = new ArrayList<>(texts.size());
    for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
      List<String> batch = texts.subList(start, Math.min(start + MAX_BATCH_SIZE, texts.size()));
      embeddings.addAll(embedBatch(batch));
    }
    return List.copyOf(embeddings);
  }

  @Override
  public String modelId() {
    return "ollama:" + model + ":" + dimensions;
  }

  private List<float[]> embedBatch(List<String> texts) {
    String body;
    try {
      body = restClient.post()
          .uri("/api/embed")
          .body(Map.of(
              "model", model,
              "input", texts,
              "dimensions", dimensions,
              "truncate", false))
          .retrieve()
          .body(String.class);
    } catch (RestClientException exception) {
      throw new IllegalStateException("Local Ollama embedding request failed", exception);
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode vectors = root == null ? null : root.get("embeddings");
      if (vectors == null || !vectors.isArray() || vectors.size() != texts.size()) {
        throw invalidResponse();
      }
      List<float[]> result = new ArrayList<>(vectors.size());
      for (JsonNode vector : vectors) {
        if (!vector.isArray() || vector.size() != dimensions) {
          throw invalidResponse();
        }
        float[] values = new float[dimensions];
        for (int index = 0; index < dimensions; index++) {
          JsonNode value = vector.get(index);
          if (!value.isNumber() || !Float.isFinite(value.floatValue())) {
            throw invalidResponse();
          }
          values[index] = value.floatValue();
        }
        result.add(values);
      }
      return result;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Local Ollama returned invalid embedding data", exception);
    }
  }

  private static IllegalStateException invalidResponse() {
    return new IllegalStateException(
        "Local Ollama returned embeddings that do not match RAG_EMBEDDING_DIMENSIONS");
  }
}
