package com.begics.rag.model;

import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.domain.TranscriptionFormats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
@ConditionalOnProperty(name = "rag.provider", havingValue = "offline", matchIfMissing = true)
class DisabledSpeechTranscription {
  @Bean
  SpeechTranscription speechTranscription() {
    return new SpeechTranscription() {
      @Override
      public boolean available() {
        return false;
      }

      @Override
      public boolean supports(String filename) {
        return TranscriptionFormats.supports(filename);
      }

      @Override
      public String transcribe(String filename, byte[] content) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Audio/video transcription requires RAG_PROVIDER=infomaniak");
      }
    };
  }
}
