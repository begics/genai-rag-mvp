package com.begics.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.begics.rag.domain.RetrievedChunk;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

final class InfomaniakReranker implements Reranker {
  private final RestClient restClient;
  private final String rerankUrl;
  private final String apiKey;
  private final String model;
  private final ProviderTextRedactor redactor;
  private final ProviderCallLimiter limiter;

  InfomaniakReranker(
      RestClient.Builder restClientBuilder, InfomaniakAiModels.InfomaniakAiProperties properties,
      ProviderTextRedactor redactor, ProviderCallLimiter limiter) {
    this(restClientBuilder, properties.rerankUrl(), properties.apiKey(), properties.rerankModel(), redactor, limiter);
  }

  InfomaniakReranker(
      RestClient.Builder restClientBuilder, String rerankUrl, String apiKey, String model,
      ProviderTextRedactor redactor, ProviderCallLimiter limiter) {
    this.restClient = restClientBuilder.build();
    this.rerankUrl = rerankUrl;
    this.apiKey = apiKey;
    this.model = model;
    this.redactor = redactor;
    this.limiter = limiter;
  }

  @Override
  public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int limit) {
    if (candidates.isEmpty() || limit <= 0) {
      return List.of();
    }
    RerankResponse response = limiter.call(ProviderCallLimiter.Operation.RERANK, () -> restClient.post()
        .uri(rerankUrl)
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + apiKey)
        .body(new RerankRequest(redactor.redact(query),
            candidates.stream().map(candidate -> redactor.redact(candidate.content())).toList(),
            model, limit))
        .retrieve()
        .body(RerankResponse.class));
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
