package com.begics.rag.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class RequestGuardrails {
  private final List<String> deniedTopics;

  public RequestGuardrails(@Value("${rag.guardrails.denied-topics:}") String configuredTopics) {
    deniedTopics = Arrays.stream(configuredTopics.split(","))
        .map(topic -> topic.strip().toLowerCase(Locale.ROOT))
        .filter(topic -> !topic.isBlank())
        .toList();
  }

  public void validateQuestion(String question) {
    String normalized = question.toLowerCase(Locale.ROOT);
    if (deniedTopics.stream().anyMatch(normalized::contains)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The question matches a configured restricted topic");
    }
  }
}
