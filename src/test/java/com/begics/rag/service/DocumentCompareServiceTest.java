package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.storage.S3DocumentReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class DocumentCompareServiceTest {
  private static final UUID WORKSPACE_ID = UUID.randomUUID();
  private final S3DocumentReader reader = Mockito.mock(S3DocumentReader.class);
  private final DocumentCompareService service = new DocumentCompareService(reader);

  @Test
  void returnsExactParagraphsAddedAndRemoved() {
    String first = "uploads/" + WORKSPACE_ID + "/v1.txt";
    String second = "uploads/" + WORKSPACE_ID + "/v2.txt";
    when(reader.read(first)).thenReturn("Keep this paragraph.\n\nOld policy.");
    when(reader.read(second)).thenReturn("Keep this paragraph.\n\nNew policy.");

    var result = service.compare(new ApiModels.DocumentCompareRequest(first, second), WORKSPACE_ID);

    assertThat(result.removedParagraphs()).containsExactly("Old policy.");
    assertThat(result.addedParagraphs()).containsExactly("New policy.");
  }

  @Test
  void rejectsKeysOutsideTheWorkspaceBeforeReading() {
    String first = "uploads/" + UUID.randomUUID() + "/v1.txt";
    String second = "uploads/" + WORKSPACE_ID + "/v2.txt";

    assertThatThrownBy(() ->
        service.compare(new ApiModels.DocumentCompareRequest(first, second), WORKSPACE_ID))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not found in this workspace");

    verifyNoInteractions(reader);
  }

  @Test
  void rejectsComparingTheSameObject() {
    String key = "uploads/" + WORKSPACE_ID + "/same.txt";

    assertThatThrownBy(() ->
        service.compare(new ApiModels.DocumentCompareRequest(key, key), WORKSPACE_ID))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("two different documents");

    verifyNoInteractions(reader);
  }

  @Test
  void rejectsMediaBeforeItCanTriggerTranscription() {
    String audio = "uploads/" + WORKSPACE_ID + "/recording.mp3";
    String text = "uploads/" + WORKSPACE_ID + "/notes.txt";

    assertThatThrownBy(() ->
        service.compare(new ApiModels.DocumentCompareRequest(audio, text), WORKSPACE_ID))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("supports text, Markdown, CSV, and PDF");

    verifyNoInteractions(reader);
  }
}
