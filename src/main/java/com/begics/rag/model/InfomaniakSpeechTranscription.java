package com.begics.rag.model;

import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.domain.TranscriptionFormats;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
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

  private final InfomaniakAiModels.InfomaniakAiProperties properties;
  private final Duration pollInterval;
  private final Duration timeout;
  private final ProviderCallLimiter limiter;
  private final Function<Duration, RestClient> requestClientFactory;

  @Autowired
  public InfomaniakSpeechTranscription(
      InfomaniakAiModels.InfomaniakAiProperties properties,
      @Value("${rag.infomaniak.transcription.poll-interval-ms:2000}") long pollIntervalMs,
      @Value("${rag.infomaniak.transcription.timeout-ms:300000}") long timeoutMs,
      ProviderCallLimiter limiter,
      InfomaniakRestClientFactory requestClientFactory) {
    this(properties, pollIntervalMs, timeoutMs, limiter, requestClientFactory::create);
  }

  public InfomaniakSpeechTranscription(
      RestClient.Builder builder,
      InfomaniakAiModels.InfomaniakAiProperties properties,
      long pollIntervalMs,
      long timeoutMs,
      ProviderCallLimiter limiter) {
    this(properties, pollIntervalMs, timeoutMs, limiter, ignored -> builder.build());
  }

  InfomaniakSpeechTranscription(
      InfomaniakAiModels.InfomaniakAiProperties properties,
      long pollIntervalMs,
      long timeoutMs,
      ProviderCallLimiter limiter,
      Function<Duration, RestClient> requestClientFactory) {
    if (pollIntervalMs <= 0 || timeoutMs <= 0) {
      throw new IllegalArgumentException("Whisper polling interval and timeout must be positive");
    }
    this.properties = properties;
    this.pollInterval = Duration.ofMillis(pollIntervalMs);
    this.timeout = Duration.ofMillis(timeoutMs);
    this.limiter = limiter;
    this.requestClientFactory = requestClientFactory;
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
    long deadline = System.nanoTime() + timeout.toNanos();
    String batchId = submit(filename, content, deadline);
    awaitCompletion(batchId, deadline);
    String transcript = download(batchId, deadline);
    if (transcript == null || transcript.isBlank()) {
      throw new IllegalStateException("Infomaniak Whisper returned an empty transcript");
    }
    return transcript;
  }

  private String submit(String filename, byte[] content, long deadline) {
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
    TranscriptionSubmission response = providerCall(
        ProviderCallLimiter.Operation.TRANSCRIPTION_SUBMIT, deadline, restClient -> restClient.post()
            .uri(properties.transcriptionUrl())
            .header("Authorization", "Bearer " + properties.apiKey())
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .body(TranscriptionSubmission.class));
    if (response == null || response.batchId() == null || response.batchId().isBlank()) {
      throw new IllegalStateException("Infomaniak did not return a Whisper batch ID");
    }
    return response.batchId();
  }

  private void awaitCompletion(String batchId, long deadline) {
    while (true) {
      TranscriptionStatus status = providerCall(
          ProviderCallLimiter.Operation.TRANSCRIPTION_POLL, deadline, restClient -> restClient.get()
              .uri(properties.transcriptionResultUrl(batchId))
              .header("Authorization", "Bearer " + properties.apiKey())
              .retrieve()
              .body(TranscriptionStatus.class));
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
          sleepBeforeNextPoll(deadline);
        }
        default -> throw new IllegalStateException("Infomaniak returned an unknown Whisper batch status");
      }
    }
  }

  private void sleepBeforeNextPoll(long deadline) {
    try {
      long remainingNanos = remainingNanos(deadline);
      TimeUnit.NANOSECONDS.sleep(Math.min(pollInterval.toNanos(), remainingNanos));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for Infomaniak Whisper", exception);
    }
  }

  private String download(String batchId, long deadline) {
    return providerCall(ProviderCallLimiter.Operation.TRANSCRIPTION_DOWNLOAD, deadline, restClient ->
        restClient.get()
            .uri(properties.transcriptionDownloadUrl(batchId))
            .header("Authorization", "Bearer " + properties.apiKey())
            .retrieve()
            .body(String.class));
  }

  private <T> T providerCall(
      ProviderCallLimiter.Operation operation, long deadline, Function<RestClient, T> action) {
    Duration remaining = Duration.ofNanos(remainingNanos(deadline));
    return limiter.call(operation, () -> {
      T result = action.apply(requestClientFactory.apply(remaining));
      remainingNanos(deadline);
      return result;
    });
  }

  private long remainingNanos(long deadline) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw new IllegalStateException("Timed out waiting for Infomaniak Whisper transcription");
    }
    return remaining;
  }

  private record TranscriptionSubmission(
      @com.fasterxml.jackson.annotation.JsonProperty("batch_id") String batchId) {}

  private record TranscriptionStatus(String status) {}
}
