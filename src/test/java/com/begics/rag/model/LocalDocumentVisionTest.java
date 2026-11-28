package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class LocalDocumentVisionTest {
  private RestClient.Builder builder;
  private MockRestServiceServer server;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl("http://localhost:11434");
    server = MockRestServiceServer.bindTo(builder).build();
  }

  @Test
  void sendsTheOriginalImageToLocalOllamaForReadingAndReview() {
    byte[] image = new byte[] {1, 2, 3};
    String encoded = Base64.getEncoder().encodeToString(image);
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("\"model\":\"gemma4:12b-mlx\""),
            org.hamcrest.Matchers.containsString("\"images\":[\"" + encoded + "\"]"),
            org.hamcrest.Matchers.containsString("weak OCR"))))
        .andRespond(withSuccess("""
            {"message":{"content":"{\\"text\\":\\"Visual transcription\\",\\"confidence\\":0.88}"}}
            """, MediaType.APPLICATION_JSON));
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Visual transcription")))
        .andRespond(withSuccess("""
            {"message":{"content":"{\\"corrected_text\\":\\"Verified transcription\\",\\"confidence\\":0.94,\\"consistent\\":true}"}}
            """, MediaType.APPLICATION_JSON));
    var vision = new LocalDocumentVision(
        builder.build(), LocalDocumentVision.Protocol.OLLAMA, "gemma4:12b-mlx",
        "gemma4:12b-mlx", JsonMapper.builder().build(), 32_768);

    var transcription = vision.readOriginal("image/png", image, "weak OCR");
    var review = vision.verifyOriginal("image/png", image, transcription.text());

    assertThat(transcription.text()).isEqualTo("Visual transcription");
    assertThat(transcription.confidence()).isEqualTo(0.88);
    assertThat(review.correctedText()).isEqualTo("Verified transcription");
    assertThat(review.consistent()).isTrue();
    assertThat(review.confidence()).isEqualTo(0.94);
    server.verify();
  }

  @Test
  void sendsImagePayloadToLocalVllmOpenAiCompatibleEndpoint() {
    byte[] image = new byte[] {4, 5};
    String encoded = Base64.getEncoder().encodeToString(image);
    server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string(org.hamcrest.Matchers.allOf(
            org.hamcrest.Matchers.containsString("data:image/jpeg;base64," + encoded),
            org.hamcrest.Matchers.containsString("\"model\":\"Qwen/Qwen-VL\""),
            org.hamcrest.Matchers.containsString("\"response_format\":{\"type\":\"json_object\"}"))))
        .andRespond(withSuccess("""
            {"choices":[{"message":{"content":"{\\"text\\":\\"Read by local vLLM\\",\\"confidence\\":0.82}"}}]}
            """, MediaType.APPLICATION_JSON));
    var vision = new LocalDocumentVision(
        builder.build(), LocalDocumentVision.Protocol.OPENAI_COMPATIBLE,
        "Qwen/Qwen-VL", "Qwen/Qwen-VL", JsonMapper.builder().build(), 32_768);

    assertThat(vision.readOriginal("image/jpeg", image, "").text()).isEqualTo("Read by local vLLM");
    server.verify();
  }

  @Test
  void rejectsOutOfRangeModelConfidence() {
    server.expect(requestTo("http://localhost:11434/api/chat"))
        .andRespond(withSuccess("""
            {"message":{"content":"{\\"text\\":\\"Visual transcription\\",\\"confidence\\":2}"}}
            """, MediaType.APPLICATION_JSON));
    var vision = new LocalDocumentVision(
        builder.build(), LocalDocumentVision.Protocol.OLLAMA,
        "gemma4:12b-mlx", "gemma4:12b-mlx", JsonMapper.builder().build(), 32_768);

    assertThatThrownBy(() -> vision.readOriginal("image/png", new byte[] {1}, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("out-of-range confidence");
  }
}
