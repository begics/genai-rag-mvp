package com.begics.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.begics.rag.domain.RetrievedChunk;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

final class InfomaniakReranker implements Reranker {
  private final RestClient restClient;
  private final InfomaniakAiModels.InfomaniakAiProperties properties;

  InfomaniakReranker(
      RestClient.Builder restClientBuilder, InfomaniakAiModels.InfomaniakAiProperties properties) {
    this.restClient = restClientBuilder.build();
    this.properties = properties;
  }

  @Override
  public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int limit) {
    if (candidates.isEmpty() || limit <= 0) {
      return List.of();
    }
    RerankResponse response = restClient.post()
        .uri(properties.rerankUrl())
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + properties.apiKey())
        .body(new RerankRequest(query, candidates.stream().map(RetrievedChunk::content).toList(),
            properties.rerankModel(), limit))
        .retrieve()
        .body(RerankResponse.class);
    if (response == null || response.results() == null) {
      throw new IllegalStateException("Infomaniak returned an empty reranking response");
    }
    return response.results().stream()
        .limit(limit)
        .map(result -> {
          if (result.index() < 0 || result.index() >= candidates.size()) {
            throw new IllegalStateException("Infomaniak returned an invalid reranking result index");
          }
          RetrievedChunk candidate = candidates.get(result.index());
          return new RetrievedChunk(candidate.documentId(), candidate.source(),
              candidate.chunk(), candidate.content(), result.relevanceScore());
        })
        .toList();
  }

  private record RerankRequest(String query, List<String> documents, String model, int top_n) {}
  private record RerankResponse(List<RerankResult> results) {}
  private record RerankResult(int index, @JsonProperty("relevance_score") double relevanceScore) {}
}
