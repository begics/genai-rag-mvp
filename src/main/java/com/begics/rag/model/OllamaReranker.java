package com.begics.rag.model;

import com.begics.rag.domain.RetrievedChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OllamaReranker implements Reranker {
  private static final String INSTRUCTION =
      "Given a web search query, retrieve relevant passages that answer the query";
  private static final String YES_TOKEN = "yes";
  private static final String NO_TOKEN = "no";
  private static final String PROMPT_PREFIX =
      "<|im_start|>system\n"
          + "Judge whether the Document meets the requirements based on the Query and the Instruct "
          + "provided. Note that the answer can only be \"yes\" or \"no\"."
          + "<|im_end|>\n<|im_start|>user\n<Instruct>: "
          + INSTRUCTION
          + "\n\n<Query>: ";
  private static final String PROMPT_MIDDLE = "\n\n<Document>: ";
  private static final String PROMPT_SUFFIX =
      "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";

  private final RestClient restClient;
  private final String model;
  private final ObjectMapper objectMapper;

  OllamaReranker(RestClient restClient, String model, ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.model = model;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int limit) {
    if (candidates.isEmpty() || limit <= 0) {
      return List.of();
    }

    List<RankedChunk> ranked = new ArrayList<>(candidates.size());
    for (RetrievedChunk candidate : candidates) {
      ranked.add(new RankedChunk(candidate, score(query, candidate.content())));
    }
    ranked.sort(Comparator.comparingDouble(RankedChunk::score).reversed());
    return ranked.stream()
        .limit(limit)
        .map(result -> {
          RetrievedChunk candidate = result.candidate();
          return new RetrievedChunk(
              candidate.documentId(), candidate.source(), candidate.chunk(), candidate.content(), result.score());
        })
        .toList();
  }

  private double score(String query, String passage) {
    String prompt = PROMPT_PREFIX
        + escapeReservedTokens(query)
        + PROMPT_MIDDLE
        + escapeReservedTokens(passage)
        + PROMPT_SUFFIX;
    Map<String, Object> request = Map.of(
        "model", model,
        "prompt", prompt,
        "raw", true,
        "stream", false,
        "logprobs", true,
        "top_logprobs", 20,
        "options", Map.of("num_predict", 1, "temperature", 0));
    try {
      String body = restClient.post()
          .uri("/api/generate")
          .contentType(MediaType.APPLICATION_JSON)
          .body(request)
          .retrieve()
          .body(String.class);
      if (body == null) {
        throw new IllegalStateException("Local Ollama returned no reranking response");
      }
      return readScore(body);
    } catch (RestClientException exception) {
      throw new IllegalStateException("Local Ollama reranking request failed", exception);
    }
  }

  private double readScore(String body) {
    try {
      JsonNode response = objectMapper.readTree(body);
      JsonNode tokenScores = response.path("logprobs").path(0).path("top_logprobs");
      if (!tokenScores.isArray()) {
        throw new IllegalStateException("Local Ollama returned no reranking token scores");
      }
      Double yesLogProbability = null;
      Double noLogProbability = null;
      for (JsonNode tokenScore : tokenScores) {
        String token = tokenScore.path("token").asString("");
        if (tokenScore.path("logprob").isNumber()) {
          double logProbability = tokenScore.path("logprob").doubleValue();
          if (YES_TOKEN.equals(token)) {
            yesLogProbability = logProbability;
          } else if (NO_TOKEN.equals(token)) {
            noLogProbability = logProbability;
          }
        }
      }
      if (yesLogProbability == null || noLogProbability == null) {
        throw new IllegalStateException("Local Ollama reranking response is missing required token scores");
      }
      return yesLogProbability - noLogProbability;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Local Ollama returned malformed reranking JSON", exception);
    }
  }

  private static String escapeReservedTokens(String value) {
    return value.replace("<|", "< |");
  }

  private record RankedChunk(RetrievedChunk candidate, double score) {}
}
