package com.begics.rag.model;

import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.domain.TranscriptionFormats;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "rag.provider", havingValue = "infomaniak")
public class InfomaniakSpeechTranscription implements SpeechTranscription {
  private static final String MODEL = "whisper";

  private final RestClient restClient;
  private final InfomaniakAiModels.InfomaniakAiProperties properties;
  private final Duration pollInterval;
  private final Duration timeout;

  public InfomaniakSpeechTranscription(
      RestClient.Builder builder,
      InfomaniakAiModels.InfomaniakAiProperties properties,
      @Value("${rag.infomaniak.transcription.poll-interval-ms:2000}") long pollIntervalMs,
      @Value("${rag.infomaniak.transcription.timeout-ms:300000}") long timeoutMs) {
    if (pollIntervalMs <= 0 || timeoutMs <= 0) {
      throw new IllegalArgumentException("Whisper polling interval and timeout must be positive");
    }
    this.restClient = builder.build();
    this.properties = properties;
    this.pollInterval = Duration.ofMillis(pollIntervalMs);
    this.timeout = Duration.ofMillis(timeoutMs);
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean supports(String filename) {
    return TranscriptionFormats.supports(filename);
  }

  @Override
  public String transcribe(String filename, byte[] content) {
    if (!supports(filename)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This audio/video format is not supported");
    }
    String batchId = submit(filename, content);
    awaitCompletion(batchId);
    String transcript = download(batchId);
    if (transcript == null || transcript.isBlank()) {
      throw new IllegalStateException("Infomaniak Whisper returned an empty transcript");
    }
    return transcript;
  }

  private String submit(String filename, byte[] content) {
    ByteArrayResource file = new ByteArrayResource(content) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", file);
    form.add("model", MODEL);
    form.add("response_format", "text");
    TranscriptionSubmission response = restClient.post()
        .uri(properties.transcriptionUrl())
        .header("Authorization", "Bearer " + properties.apiKey())
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .retrieve()
        .body(TranscriptionSubmission.class);
    if (response == null || response.batchId() == null || response.batchId().isBlank()) {
      throw new IllegalStateException("Infomaniak did not return a Whisper batch ID");
    }
    return response.batchId();
  }

  private void awaitCompletion(String batchId) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      TranscriptionStatus status = restClient.get()
          .uri(properties.transcriptionResultUrl(batchId))
          .header("Authorization", "Bearer " + properties.apiKey())
          .retrieve()
          .body(TranscriptionStatus.class);
      if (status == null || status.status() == null) {
        throw new IllegalStateException("Infomaniak returned an invalid Whisper batch status");
      }
      switch (status.status()) {
        case "success" -> {
          return;
        }
        case "failed", "cancelled" -> throw new IllegalStateException(
            "Infomaniak Whisper transcription " + status.status());
        case "pending", "processing" -> {
          if (System.nanoTime() >= deadline) {
            throw new IllegalStateException("Timed out waiting for Infomaniak Whisper transcription");
          }
          sleepBeforeNextPoll();
        }
        default -> throw new IllegalStateException("Infomaniak returned an unknown Whisper batch status");
      }
    }
  }

  private void sleepBeforeNextPoll() {
    try {
      Thread.sleep(pollInterval.toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for Infomaniak Whisper", exception);
    }
  }

  private String download(String batchId) {
    return restClient.get()
        .uri(properties.transcriptionDownloadUrl(batchId))
        .header("Authorization", "Bearer " + properties.apiKey())
        .retrieve()
        .body(String.class);
  }

  private record TranscriptionSubmission(
      @com.fasterxml.jackson.annotation.JsonProperty("batch_id") String batchId) {}

  private record TranscriptionStatus(String status) {}
}
