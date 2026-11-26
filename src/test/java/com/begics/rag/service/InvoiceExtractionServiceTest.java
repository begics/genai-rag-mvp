package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels.InvoiceExtractionResponse;
import com.begics.rag.contract.ApiModels.InvoiceField;
import com.begics.rag.domain.InvoiceExtraction;
import com.begics.rag.model.InvoiceExtractor;
import com.begics.rag.storage.S3DocumentReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class InvoiceExtractionServiceTest {
  private final S3DocumentReader documents = Mockito.mock(S3DocumentReader.class);
  private final InvoiceExtractor extractor = Mockito.mock(InvoiceExtractor.class);
  private final InvoiceExtractionService service = new InvoiceExtractionService(documents, extractor);

  @Test
  void readsOnlyAnInvoiceDocumentInTheRequestedWorkspace() {
    UUID workspaceId = UUID.randomUUID();
    String key = "uploads/" + workspaceId + "/invoice.pdf";
    InvoiceExtractionResponse expected = new InvoiceExtractionResponse(
        new InvoiceField<>("ACME", "ACME"),
        new InvoiceField<>(null, null),
        new InvoiceField<>(null, null),
        new InvoiceField<>(null, null),
        new InvoiceField<>(null, null),
        new InvoiceField<>(null, null));
    InvoiceExtraction extraction = new InvoiceExtraction(
        new com.begics.rag.domain.InvoiceField<>("ACME", "ACME"),
        new com.begics.rag.domain.InvoiceField<>(null, null),
        new com.begics.rag.domain.InvoiceField<>(null, null),
        new com.begics.rag.domain.InvoiceField<>(null, null),
        new com.begics.rag.domain.InvoiceField<>(null, null),
        new com.begics.rag.domain.InvoiceField<>(null, null));
    when(documents.read(key)).thenReturn("ACME invoice");
    when(extractor.extract("ACME invoice")).thenReturn(extraction);

    assertThat(service.extract(key, workspaceId)).isEqualTo(expected);

    verify(documents).read(key);
    verify(extractor).extract("ACME invoice");
  }

  @Test
  void rejectsKeysOutsideTheWorkspaceBeforeReadingTheObject() {
    UUID workspaceId = UUID.randomUUID();

    assertThatThrownBy(() -> service.extract("uploads/another-workspace/invoice.pdf", workspaceId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not found in this workspace");

    verifyNoInteractions(documents, extractor);
  }

  @Test
  void rejectsUnsupportedFilesBeforeCallingTheExtractor() {
    UUID workspaceId = UUID.randomUUID();
    String key = "uploads/" + workspaceId + "/recording.mp3";

    assertThatThrownBy(() -> service.extract(key, workspaceId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("supports text, Markdown, CSV, and readable PDF");

    verifyNoInteractions(documents, extractor);
  }

  @Test
  void enforcesAnExtractionTextLimit() {
    UUID workspaceId = UUID.randomUUID();
    String key = "uploads/" + workspaceId + "/invoice.txt";
    when(documents.read(key)).thenReturn("x".repeat(100_001));

    assertThatThrownBy(() -> service.extract(key, workspaceId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("100,000 extracted characters");

    verifyNoInteractions(extractor);
  }
}
