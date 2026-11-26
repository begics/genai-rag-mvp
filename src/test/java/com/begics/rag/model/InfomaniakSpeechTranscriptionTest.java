package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class InfomaniakSpeechTranscriptionTest {
  private static final InfomaniakAiModels.InfomaniakAiProperties PROPERTIES =
      new InfomaniakAiModels.InfomaniakAiProperties(
          114047, "test-secret", "chat", "embedding", "reranker", 3_584);

  @Test
  void submitsAudioPollsTheAsyncBatchAndDownloadsItsTranscript() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(PROPERTIES.transcriptionUrl()))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ")))
        .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"file\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"model\"")))
        .andRespond(withSuccess("{\"batch_id\":\"batch-123\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(PROPERTIES.transcriptionResultUrl("batch-123")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"status\":\"processing\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(PROPERTIES.transcriptionResultUrl("batch-123")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(PROPERTIES.transcriptionDownloadUrl("batch-123")))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("The searchable transcript.", MediaType.TEXT_PLAIN));

    var transcription = new InfomaniakSpeechTranscription(builder, PROPERTIES, 1, 1_000);

    assertThat(transcription.transcribe("meeting.mp3", new byte[] {'I', 'D', '3'}))
        .isEqualTo("The searchable transcript.");
    server.verify();
  }

  @Test
  void reportsProviderBatchFailures() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo(PROPERTIES.transcriptionUrl()))
        .andRespond(withSuccess("{\"batch_id\":\"batch-123\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(PROPERTIES.transcriptionResultUrl("batch-123")))
        .andRespond(withSuccess("{\"status\":\"failed\"}", MediaType.APPLICATION_JSON));

    var transcription = new InfomaniakSpeechTranscription(builder, PROPERTIES, 1, 1_000);

    assertThatThrownBy(() -> transcription.transcribe("meeting.mp3", new byte[] {'I', 'D', '3'}))
        .hasMessageContaining("transcription failed");
    server.verify();
  }
}
