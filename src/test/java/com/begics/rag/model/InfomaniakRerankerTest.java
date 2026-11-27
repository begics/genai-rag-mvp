package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.begics.rag.domain.RetrievedChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InfomaniakRerankerTest {
  @Test
  void returnsCandidatesInTheOrderSelectedByInfomaniak() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    var properties = new InfomaniakAiModels.InfomaniakAiProperties(
        114047, "test-secret", "chat", "planner", "embedding", "BAAI/bge-reranker-v2-m3", 3_584, 512);
    server.expect(requestTo(properties.rerankUrl()))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-secret"))
        .andRespond(withSuccess("""
            {"results":[
              {"index":1,"relevance_score":0.97},
              {"index":0,"relevance_score":0.42}
            ]}
            """, MediaType.APPLICATION_JSON));
    Reranker reranker = new InfomaniakReranker(
        builder, properties, new ProviderTextRedactor(), new ProviderCallLimiter(new SimpleMeterRegistry(), 2));
    List<RetrievedChunk> candidates = List.of(
        new RetrievedChunk("d1", "one.txt", 0, "first", 0.8),
        new RetrievedChunk("d2", "two.txt", 1, "second", 0.7));

    List<RetrievedChunk> ranked = reranker.rerank("question", candidates, 2);

    assertThat(ranked).extracting(RetrievedChunk::content)
        .containsExactly("second", "first");
    assertThat(ranked).extracting(RetrievedChunk::score)
        .containsExactly(0.97, 0.42);
    server.verify();
  }

  @Test
  void masksPiiBeforeSendingQuestionsAndPassagesToReranking() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    var properties = new InfomaniakAiModels.InfomaniakAiProperties(
        114047, "test-secret", "chat", "planner", "embedding", "reranker", 3_584, 512);
    server.expect(requestTo(properties.rerankUrl()))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("[REDACTED_EMAIL]")))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("orbit.synthetic@example.test"))))
        .andRespond(withSuccess("""
            {"results":[{"index":0,"relevance_score":0.9}]}
            """, MediaType.APPLICATION_JSON));
    Reranker reranker = new InfomaniakReranker(
        builder, properties, new ProviderTextRedactor(), new ProviderCallLimiter(new SimpleMeterRegistry(), 2));
    RetrievedChunk candidate = new RetrievedChunk(
        "d1", "one.txt", 0, "Contact orbit.synthetic@example.test", 0.8);

    reranker.rerank("Find orbit.synthetic@example.test", List.of(candidate), 1);

    server.verify();
  }
}
