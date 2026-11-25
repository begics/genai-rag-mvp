package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.begics.rag.storage.S3ObjectWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class DocumentUploadServiceTest {
  private final S3ObjectWriter objectWriter = org.mockito.Mockito.mock(S3ObjectWriter.class);
  private final DocumentUploadService service = new DocumentUploadService(objectWriter);

  @Test
  void uploadsTextAndPdfAsIndexableFiles() {
    for (String filename : new String[] {"notes.txt", "manual.PDF", "data.csv"}) {
      MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream",
          "content".getBytes());

      var uploaded = service.upload(file);

      assertThat(uploaded.filename()).isEqualTo(filename);
      assertThat(uploaded.indexable()).isTrue();
      assertThat(uploaded.key()).startsWith("uploads/");
      verify(objectWriter).write(uploaded.key(), uploaded.contentType(), file);
    }
  }

  @Test
  void uploadsAudioAndVideoButMarksThemNotIndexable() {
    for (String filename : new String[] {"talk.mp3", "demo.mp4"}) {
      MockMultipartFile file = new MockMultipartFile("file", filename, "application/octet-stream",
          new byte[] {1, 2, 3});

      var uploaded = service.upload(file);

      assertThat(uploaded.indexable()).isFalse();
      verify(objectWriter).write(uploaded.key(), uploaded.contentType(), file);
    }
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
  void sanitizesUploadedFilenameAndKeepsItWithinAnOpaqueUniqueKey() {
    MockMultipartFile file = new MockMultipartFile("file", "../../private notes.pdf", "application/pdf",
        "content".getBytes());

    var uploaded = service.upload(file);

    assertThat(uploaded.filename()).isEqualTo("private notes.pdf");
    assertThat(uploaded.key()).endsWith("/private notes.pdf");
  }
}
