package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.begics.rag.store.DocumentStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class DocumentContentServiceTest {
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private final DocumentStore documents = Mockito.mock(DocumentStore.class);
  private final DocumentContentService service = new DocumentContentService(documents);

  @Test
  void returnsBoundedContextAroundTheExactCitation() {
    UUID documentId = UUID.randomUUID();
    String key = "uploads/" + WORKSPACE_ID + "/manual.txt";
    String text = "a".repeat(1_200) + "exact citation" + "b".repeat(100);
    when(documents.sourceChunk(WORKSPACE_ID, documentId, 4))
        .thenReturn(Optional.of(new DocumentStore.SourceChunk(key, text)));

    var result = service.read(WORKSPACE_ID, documentId, 4, "exact citation");

    assertThat(result.filename()).isEqualTo("manual.txt");
    assertThat(result.matchOffset()).isEqualTo(1_000);
    assertThat(result.text()).hasSize(text.length() - 200).contains("exact citation");
  }

  @Test
  void doesNotReadDocumentsFromAnotherWorkspace() {
    UUID documentId = UUID.randomUUID();
    when(documents.sourceChunk(WORKSPACE_ID, documentId, 0)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.read(WORKSPACE_ID, documentId, 0, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Document not found");
  }

  @Test
  void returnsNotFoundForUnknownWorkspaceDocumentId() {
    UUID documentId = UUID.randomUUID();
    when(documents.sourceChunk(WORKSPACE_ID, documentId, 0)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.read(WORKSPACE_ID, documentId, 0, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void rejectsNegativeChunkNumbers() {
    assertThatThrownBy(() -> service.read(WORKSPACE_ID, UUID.randomUUID(), -1, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("must not be negative");
  }
}
