package com.begics.rag.model;

import com.begics.rag.domain.InvoiceExtraction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "rag.provider", havingValue = "offline", matchIfMissing = true)
public class DisabledInvoiceExtractor implements InvoiceExtractor {
  @Override
  public InvoiceExtraction extract(String text) {
    throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "Invoice extraction requires RAG_PROVIDER=infomaniak");
  }
}
