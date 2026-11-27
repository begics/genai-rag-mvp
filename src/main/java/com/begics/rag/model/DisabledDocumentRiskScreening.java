package com.begics.rag.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "rag.provider", havingValue = "offline", matchIfMissing = true)
public class DisabledDocumentRiskScreening implements DocumentRiskScreening {
  @Override
  public ScreeningResult evaluate(String text) {
    throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Document screening requires RAG_PROVIDER=infomaniak");
  }
}
