package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;

import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.storage.S3ObjectWriter;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

class DocumentUploadServiceTest {
  private final S3ObjectWriter objectWriter = org.mockito.Mockito.mock(S3ObjectWriter.class);
  private final StorageQuotaService storageQuota = org.mockito.Mockito.mock(StorageQuotaService.class);
  private final SpeechTranscription transcription = org.mockito.Mockito.mock(SpeechTranscription.class);
  private final DocumentUploadService service =
      new DocumentUploadService(objectWriter, storageQuota, transcription);

  @Test
  void uploadsTextAndPdfAsIndexableFiles() {
    for (String filename : new String[] {"notes.txt", "manual.PDF", "data.csv"}) {
      MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream",
          (filename.endsWith(".PDF") ? "%PDF-1.7 content" : "content").getBytes(StandardCharsets.UTF_8));

      var uploaded = service.upload(file);

      assertThat(uploaded.filename()).isEqualTo(filename);
      assertThat(uploaded.indexable()).isTrue();
      assertThat(uploaded.key()).startsWith("uploads/");
      verify(objectWriter).write(uploaded.key(), uploaded.contentType(), file);
    }
    verify(storageQuota, times(3)).checkS3Upload(eq("legacy-test-subject"),
        eq(WorkspaceAccessService.LEGACY_WORKSPACE_ID), anyLong());
  }

  @Test
  void acceptsFilesAtTheTwentyMebibyteUploadLimit() {
    byte[] content = new byte[20 * 1024 * 1024];
    java.util.Arrays.fill(content, (byte) 'a');
    MockMultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain", content);

    var uploaded = service.upload(file);

    assertThat(uploaded.sizeBytes()).isEqualTo(20L * 1024 * 1024);
    verify(objectWriter).write(uploaded.key(), uploaded.contentType(), file);
  }

  @Test
  void rejectsFilesOverTheTwentyMebibyteLimitForEveryFileType() {
    byte[] content = new byte[20 * 1024 * 1024 + 1];
    java.util.Arrays.fill(content, (byte) 'a');
    MockMultipartFile textFile = new MockMultipartFile("file", "large.txt", "text/plain", content);
    content[0] = 'I';
    content[1] = 'D';
    content[2] = '3';
    MockMultipartFile audioFile = new MockMultipartFile("file", "large.mp3", "audio/mpeg", content);

    for (MockMultipartFile file : new MockMultipartFile[] {textFile, audioFile}) {
      assertThatThrownBy(() -> service.upload(file))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("Files must be 20 MiB or smaller")
          .extracting(error -> ((ResponseStatusException) error).getStatusCode())
          .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }
    verifyNoInteractions(objectWriter);
  }

  @Test
  void uploadsAudioAndVideoButMarksThemNotIndexable() {
    for (String filename : new String[] {"talk.mp3", "demo.mp4"}) {
      MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream",
          filename.endsWith(".mp3")
              ? "ID3".getBytes(StandardCharsets.US_ASCII)
              : new byte[] {0, 0, 0, 0, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});

      var uploaded = service.upload(file);

      assertThat(uploaded.indexable()).isFalse();
      verify(objectWriter).write(uploaded.key(), uploaded.contentType(), file);
    }
  }

  @Test
  void marksSupportedAudioAsIndexableWhenTranscriptionIsConfigured() {
    org.mockito.Mockito.when(transcription.available()).thenReturn(true);
    MockMultipartFile file = new MockMultipartFile(
        "file", "talk.mp3", "application/octet-stream", "ID3".getBytes(StandardCharsets.US_ASCII));

    var uploaded = service.upload(file);

    assertThat(uploaded.indexable()).isTrue();
    verify(objectWriter).write(uploaded.key(), uploaded.contentType(), file);
  }

  @Test
  void rejectsUnsupportedFilesWithoutWritingToStorage() {
    MockMultipartFile file = new MockMultipartFile("file", "program.exe", "application/octet-stream",
        new byte[] {1});

    assertThatThrownBy(() -> service.upload(file))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Supported file types");
    verifyNoInteractions(objectWriter);
  }

  @Test
  void rejectsVideoFormatsWithoutAConfiguredTranscriptionFlow() {
    MockMultipartFile file = new MockMultipartFile("file", "clip.mov", "video/quicktime",
        new byte[] {0, 0, 0, 0, 'f', 't', 'y', 'p', 'q', 't', ' ', ' '});

    assertThatThrownBy(() -> service.upload(file))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Supported file types");
    verifyNoInteractions(objectWriter);
  }

  @Test
  void sanitizesUploadedFilenameAndKeepsItWithinAnOpaqueUniqueKey() {
    MockMultipartFile file = new MockMultipartFile("file", "../../private notes.pdf", "application/pdf",
        "%PDF-1.7 content".getBytes(StandardCharsets.UTF_8));

    var uploaded = service.upload(file);

    assertThat(uploaded.filename()).isEqualTo("private notes.pdf");
    assertThat(uploaded.key()).endsWith("/private notes.pdf");
  }

  @Test
  void rejectsFilesWhoseContentDoesNotMatchTheirExtension() {
    MockMultipartFile executable = new MockMultipartFile(
        "file", "notes.pdf", "application/pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));
    MockMultipartFile invalidUtf8 = new MockMultipartFile(
        "file", "notes.txt", "text/plain", new byte[] {(byte) 0xc3, (byte) 0x28});

    assertThatThrownBy(() -> service.upload(executable))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("does not match");
    assertThatThrownBy(() -> service.upload(invalidUtf8))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("does not match");
    verifyNoInteractions(objectWriter);
  }
}
