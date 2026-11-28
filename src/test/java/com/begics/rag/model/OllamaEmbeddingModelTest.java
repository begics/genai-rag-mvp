package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OllamaEmbeddingModelTest {
  @Test
  void requestsTheConfiguredEmbeddingDimensionsAndValidatesReturnedVectors() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://localhost:11434/api/embed"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("\"model\":\"qwen3-embedding:8b\""),
            org.hamcrest.Matchers.containsString("\"dimensions\":4"),
            org.hamcrest.Matchers.containsString("\"truncate\":false"),
            org.hamcrest.Matchers.containsString("\"input\":[\"first\",\"second\"]"))))
        .andRespond(withSuccess("""
            {"embeddings":[[0.1,0.2,0.3,0.4],[0.5,0.6,0.7,0.8]]}
            """, MediaType.APPLICATION_JSON));
    var embeddings = new OllamaEmbeddingModel(
        builder.build(), "qwen3-embedding:8b", 4, JsonMapper.builder().build());

    var result = embeddings.embedAll(java.util.List.of("first", "second"));

    assertThat(result).hasSize(2);
    assertThat(result.getFirst()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
    assertThat(embeddings.modelId()).isEqualTo("ollama:qwen3-embedding:8b:4");
    server.verify();
  }

  @Test
  void rejectsVectorsThatDoNotMatchTheConfiguredDatabaseDimension() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:11434");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    String vector = IntStream.range(0, 3).mapToObj(ignored -> "0.1").collect(Collectors.joining(",", "[", "]"));
    server.expect(requestTo("http://localhost:11434/api/embed"))
        .andRespond(withSuccess("{\"embeddings\":[" + vector + "]}", MediaType.APPLICATION_JSON));
    var embeddings = new OllamaEmbeddingModel(builder.build(), "qwen3-embedding:test", 4,
        JsonMapper.builder().build());

    assertThatThrownBy(() -> embeddings.embed("text"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("do not match RAG_EMBEDDING_DIMENSIONS");
    server.verify();
  }
}
