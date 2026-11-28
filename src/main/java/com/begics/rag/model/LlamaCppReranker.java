package com.begics.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.begics.rag.domain.RetrievedChunk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class LlamaCppReranker implements Reranker {
  private final RestClient restClient;
  private final String model;

  LlamaCppReranker(RestClient restClient, String model) {
    this.restClient = restClient;
    this.model = model;
  }

  @Override
  public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int limit) {
    if (candidates.isEmpty() || limit <= 0) {
      return List.of();
    }

    int topN = Math.min(limit, candidates.size());
    RerankResponse response;
    try {
      response = restClient.post()
          .uri("/v1/rerank")
          .contentType(MediaType.APPLICATION_JSON)
          .body(new RerankRequest(
              model, query, candidates.stream().map(RetrievedChunk::content).toList(), topN))
          .retrieve()
          .body(RerankResponse.class);
    } catch (RestClientException exception) {
      throw new IllegalStateException("Local llama.cpp reranking request failed", exception);
    }
    if (response == null || response.results() == null || response.results().isEmpty()) {
      throw new IllegalStateException("Local llama.cpp returned an empty reranking response");
    }
    if (response.results().size() < topN) {
      throw new IllegalStateException("Local llama.cpp returned an incomplete reranking response");
    }

    List<RetrievedChunk> ranked = new ArrayList<>(Math.min(topN, response.results().size()));
    Set<Integer> seenIndexes = new HashSet<>();
    for (RerankResult result : response.results().stream().limit(topN).toList()) {
      if (result.index() < 0 || result.index() >= candidates.size()
          || !seenIndexes.add(result.index()) || result.relevanceScore() == null
          || !Double.isFinite(result.relevanceScore())) {
        throw new IllegalStateException("Local llama.cpp returned an invalid reranking result");
      }
      RetrievedChunk candidate = candidates.get(result.index());
      ranked.add(new RetrievedChunk(candidate.documentId(), candidate.source(),
          candidate.chunk(), candidate.content(), result.relevanceScore()));
    }
    return List.copyOf(ranked);
  }

  private record RerankRequest(String model, String query, List<String> documents, int top_n) {}
  private record RerankResponse(List<RerankResult> results) {}
  private record RerankResult(int index, @JsonProperty("relevance_score") Double relevanceScore) {}
}
