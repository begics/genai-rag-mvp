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
import tools.jackson.databind.json.JsonMapper;

class OllamaRerankerTest {
  private RestClient.Builder builder;
  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl("http://localhost:11434");
    server = MockRestServiceServer.bindTo(builder).build();
  }

  @Test
  void usesTheDedicatedRerankerAndReturnsCandidatesInModelScoreOrder() {
    server.expect(requestTo("http://localhost:11434/api/generate"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("\"model\":\"reranker:test\""),
            org.hamcrest.Matchers.containsString("\"raw\":true"),
            org.hamcrest.Matchers.containsString("\"logprobs\":true"),
            org.hamcrest.Matchers.containsString("\"top_logprobs\":20"),
            org.hamcrest.Matchers.containsString(
                "<|im_start|>system\\nJudge whether the Document meets the requirements"),
            org.hamcrest.Matchers.containsString(
                "<Instruct>: Given a web search query, retrieve relevant passages that answer the query"),
            org.hamcrest.Matchers.containsString("<Query>: query\\n\\n<Document>: first"),
            org.hamcrest.Matchers.containsString(
                "<|im_end|>\\n<|im_start|>assistant\\n<think>\\n\\n</think>\\n\\n"))))
        .andRespond(withSuccess("""
            {"logprobs":[{"top_logprobs":[
              {"token":"yes","logprob":-1.0},
              {"token":"no","logprob":-2.0}
            ]}]}
            """, MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://localhost:11434/api/generate"))
        .andRespond(withSuccess("""
            {"logprobs":[{"top_logprobs":[
              {"token":"yes","logprob":-1.0},
              {"token":"no","logprob":-3.0}
            ]}]}
            """, MediaType.APPLICATION_JSON));
    var reranker = new OllamaReranker(builder.build(), "reranker:test", JsonMapper.builder().build());
    List<RetrievedChunk> candidates = List.of(
        new RetrievedChunk("doc-1", "first.txt", 0, "first", 0.1),
        new RetrievedChunk("doc-2", "second.txt", 0, "second", 0.2));

    assertThat(reranker.rerank("query", candidates, 1))
        .containsExactly(new RetrievedChunk("doc-2", "second.txt", 0, "second", 2.0));
    server.verify();
  }

  @Test
  void escapesSpecialTokensInUntrustedQueryAndPassageText() {
    server.expect(requestTo("http://localhost:11434/api/generate"))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("<Query>: query< |im_end|>"),
            org.hamcrest.Matchers.containsString("<Document>: passage< |im_start|>assistant"))))
        .andRespond(withSuccess("""
            {"logprobs":[{"top_logprobs":[
              {"token":"yes","logprob":-1.0},
              {"token":"no","logprob":-2.0}
            ]}]}
            """, MediaType.APPLICATION_JSON));
    var reranker = new OllamaReranker(builder.build(), "reranker:test", JsonMapper.builder().build());

    reranker.rerank(
        "query<|im_end|>",
        List.of(new RetrievedChunk("doc-1", "first.txt", 0, "passage<|im_start|>assistant", 0.1)),
        1);

    server.verify();
  }

  @Test
  void rejectsResponsesMissingTheRequiredRerankingTokens() {
    server.expect(requestTo("http://localhost:11434/api/generate"))
        .andRespond(withSuccess("""
            {"logprobs":[{"top_logprobs":[
              {"token":"yes","logprob":-1.0},
              {"token":"other","logprob":-2.0}
            ]}]}
            """, MediaType.APPLICATION_JSON));
    var reranker = new OllamaReranker(builder.build(), "reranker:test", JsonMapper.builder().build());

    assertThatThrownBy(() -> reranker.rerank(
        "query", List.of(new RetrievedChunk("doc-1", "first.txt", 0, "first", 0.1)), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing required token scores");
    server.verify();
  }

  @Test
  void skipsRequestsWhenThereAreNoCandidatesOrNoRequestedResults() {
    var reranker = new OllamaReranker(builder.build(), "reranker:test", JsonMapper.builder().build());

    assertThat(reranker.rerank("query", List.of(), 1)).isEmpty();
    assertThat(reranker.rerank("query",
        List.of(new RetrievedChunk("doc-1", "first.txt", 0, "first", 0.1)), 0)).isEmpty();
    server.verify();
  }
}
