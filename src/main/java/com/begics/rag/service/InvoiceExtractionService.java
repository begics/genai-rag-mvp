package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.domain.InvoiceExtraction;
import com.begics.rag.model.InvoiceExtractor;
import com.begics.rag.storage.S3DocumentReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InvoiceExtractionService {
  private static final int MAX_EXTRACTION_TEXT_CHARACTERS = 100_000;

  private final S3DocumentReader documents;
  private final InvoiceExtractor extractor;

  public InvoiceExtractionService(S3DocumentReader documents, InvoiceExtractor extractor) {
    this.documents = documents;
    this.extractor = extractor;
  }

  public ApiModels.InvoiceExtractionResponse extract(String key, UUID workspaceId) {
    WorkspaceAccessService.requireObjectKey(key, workspaceId);
    if (!isSupportedFilename(key)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Invoice extraction supports text, Markdown, CSV, and readable PDF files");
    }
    String text = documents.read(key);
    if (text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document contains no extractable text");
    }
    if (text.length() > MAX_EXTRACTION_TEXT_CHARACTERS) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
          "Invoice extraction is limited to 100,000 extracted characters per document");
    }
    InvoiceExtraction extraction = extractor.extract(text);
    return new ApiModels.InvoiceExtractionResponse(
        invoiceField(extraction.supplier()),
        invoiceField(extraction.invoiceNumber()),
        invoiceField(extraction.invoiceDate()),
        invoiceField(extraction.dueDate()),
        invoiceField(extraction.currency()),
        invoiceField(extraction.total()),
        validate(extraction));
  }

  private static List<ApiModels.InvoiceValidationIssue> validate(InvoiceExtraction extraction) {
    List<ApiModels.InvoiceValidationIssue> issues = new ArrayList<>();
    if (extraction.invoiceDate().value() != null && extraction.dueDate().value() != null
        && extraction.dueDate().value().isBefore(extraction.invoiceDate().value())) {
      issues.add(new ApiModels.InvoiceValidationIssue(
          "dueDate", "Due date is earlier than the invoice date; review the extracted dates"));
    }
    if ((extraction.total().value() == null) != (extraction.currency().value() == null)) {
      issues.add(new ApiModels.InvoiceValidationIssue(
          "total", "Total and currency should either both be present or both be missing"));
    }
    return List.copyOf(issues);
  }

  private static <T> ApiModels.InvoiceField<T> invoiceField(com.begics.rag.domain.InvoiceField<T> field) {
    return new ApiModels.InvoiceField<>(field.value(), field.evidence());
  }

  private static boolean isSupportedFilename(String key) {
    String lower = key.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".txt") || lower.endsWith(".md")
        || lower.endsWith(".csv") || lower.endsWith(".pdf");
  }
}
