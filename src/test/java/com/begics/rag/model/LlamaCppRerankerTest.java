package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.begics.rag.domain.RetrievedChunk;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LlamaCppRerankerTest {
  private RestClient.Builder builder;
  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl("http://localhost:8081");
    server = MockRestServiceServer.bindTo(builder).build();
  }

  @Test
  void sendsQueryAndDocumentsToLlamaCppAndUsesTheReturnedRanking() {
    server.expect(requestTo("http://localhost:8081/v1/rerank"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("\"model\":\"paulalesius/Qwen3-Reranker-0.6B-GGUF:Q6_K\""),
            org.hamcrest.Matchers.containsString("\"query\":\"question\""),
            org.hamcrest.Matchers.containsString("\"documents\":[\"first passage\",\"second passage\"]"),
            org.hamcrest.Matchers.containsString("\"top_n\":1"))))
        .andRespond(withSuccess("""
            {"results":[{"index":1,"relevance_score":0.95}]}
            """, MediaType.APPLICATION_JSON));
    var reranker = new LlamaCppReranker(
        builder.build(), "paulalesius/Qwen3-Reranker-0.6B-GGUF:Q6_K");
    List<RetrievedChunk> candidates = List.of(
        new RetrievedChunk("doc-1", "first.txt", 0, "first passage", 0.1),
        new RetrievedChunk("doc-2", "second.txt", 0, "second passage", 0.2));

    assertThat(reranker.rerank("question", candidates, 1))
        .containsExactly(new RetrievedChunk("doc-2", "second.txt", 0, "second passage", 0.95));
    server.verify();
  }

  @Test
  void rejectsInvalidResultIndexesAndDoesNotCallTheServerForEmptyInput() {
    server.expect(requestTo("http://localhost:8081/v1/rerank"))
        .andRespond(withSuccess("""
            {"results":[{"index":2,"relevance_score":0.95}]}
            """, MediaType.APPLICATION_JSON));
    var reranker = new LlamaCppReranker(builder.build(), "qwen3-reranker");

    assertThat(reranker.rerank("question", List.of(), 1)).isEmpty();
    assertThatThrownBy(() -> reranker.rerank(
        "question", List.of(new RetrievedChunk("doc-1", "one.txt", 0, "passage", 0.1)), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid reranking result");
    server.verify();
  }

  @Test
  void rejectsIncompleteRankingResponses() {
    server.expect(requestTo("http://localhost:8081/v1/rerank"))
        .andRespond(withSuccess("""
            {"results":[{"index":0,"relevance_score":0.95}]}
            """, MediaType.APPLICATION_JSON));
    var reranker = new LlamaCppReranker(builder.build(), "qwen3-reranker");

    assertThatThrownBy(() -> reranker.rerank("question", List.of(
        new RetrievedChunk("doc-1", "one.txt", 0, "passage one", 0.1),
        new RetrievedChunk("doc-2", "two.txt", 0, "passage two", 0.2)), 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("incomplete reranking response");
    server.verify();
  }
}
