package com.begics.rag.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.begics.rag.domain.SpeechTranscription;
import org.mockito.InOrder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

class S3DocumentReaderTest {
  private final S3Client s3 = mock(S3Client.class);
  private final SpeechTranscription transcription = mock(SpeechTranscription.class);
  private final FileMalwareScanner malwareScanner = mock(FileMalwareScanner.class);
  private final S3DocumentReader reader =
      new S3DocumentReader(s3, "rag-documents", transcription, malwareScanner);

  @Test
  void readsUtf8ObjectFromConfiguredBucketAndKey() {
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream("hello RustFS".getBytes(StandardCharsets.UTF_8)));

    assertThat(reader.read("notes.txt")).isEqualTo("hello RustFS");
    verify(s3).getObject(GetObjectRequest.builder().bucket("rag-documents").key("notes.txt").build());
  }

  @Test
  void extractsTextFromPdfObjects() throws IOException {
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(pdf("Text extracted from PDF")));

    assertThat(reader.read("notes.pdf")).contains("Text extracted from PDF");
  }

  @Test
  void rejectsPdfObjectsAboveThePageLimit() throws IOException {
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(pdfWithPages(
        S3DocumentReader.MAX_PDF_PAGES + 1)));

    assertThatThrownBy(() -> reader.read("many-pages.pdf"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void rejectsPdfObjectsAboveTheExtractedTextLimit() throws IOException {
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(pdf(
        "a".repeat(S3DocumentReader.MAX_PDF_EXTRACTED_CHARACTERS + 1))));

    assertThatThrownBy(() -> reader.read("large-text.pdf"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void rejectsMalformedPdfObjects() {
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream("%PDF-not valid".getBytes(StandardCharsets.US_ASCII)));

    assertThatThrownBy(() -> reader.read("broken.pdf"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsOversizedObjects() {
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(new byte[20 * 1024 * 1024 + 1]));

    assertThatThrownBy(() -> reader.read("large.txt"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void acceptsObjectsAtTheTwentyMebibyteLimit() {
    byte[] content = new byte[20 * 1024 * 1024];
    java.util.Arrays.fill(content, (byte) 'a');
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(content));

    assertThat(reader.read("limit.txt")).hasSize(content.length);
  }

  @Test
  void rejectsOversizedObjectsBeforeCreatingAnIndexingJob() {
    when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
        HeadObjectResponse.builder().contentLength(20L * 1024 * 1024 + 1).build());

    assertThatThrownBy(() -> reader.validateIndexableSize("large.txt"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Only files up to 20 MiB can be indexed")
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @Test
  void acceptsObjectsAtTheIndexingLimitBeforeCreatingAnIndexingJob() {
    when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
        HeadObjectResponse.builder().contentLength(20L * 1024 * 1024).build());

    reader.validateIndexableSize("limit.txt");

    verify(s3).headObject(HeadObjectRequest.builder().bucket("rag-documents").key("limit.txt").build());
  }

  @Test
  void transcribesSupportedAudioObjects() {
    byte[] audio = "ID3 audio".getBytes(StandardCharsets.US_ASCII);
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(audio));
    when(transcription.available()).thenReturn(true);
    when(transcription.transcribe("talk.mp3", audio)).thenReturn("Searchable speech transcript");

    assertThat(reader.read("uploads/workspace/talk.mp3")).isEqualTo("Searchable speech transcript");
    InOrder scanBeforeTranscription = org.mockito.Mockito.inOrder(malwareScanner, transcription);
    scanBeforeTranscription.verify(malwareScanner).scan(audio);
    scanBeforeTranscription.verify(transcription).transcribe("talk.mp3", audio);
  }

  @Test
  void blocksDetectedMalwareBeforeExtractionOrTranscription() {
    byte[] audio = "ID3 infected audio".getBytes(StandardCharsets.US_ASCII);
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(audio));
    when(transcription.available()).thenReturn(true);
    org.mockito.Mockito.doThrow(new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY, "Antivirus detected malware; indexing is blocked"))
        .when(malwareScanner).scan(audio);

    assertThatThrownBy(() -> reader.read("uploads/workspace/talk.mp3"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

    org.mockito.Mockito.verifyNoInteractions(transcription);
  }

  @Test
  void failsClosedWhenAntivirusScannerIsUnavailable() {
    byte[] text = "plain document".getBytes(StandardCharsets.UTF_8);
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(text));
    org.mockito.Mockito.doThrow(new IllegalStateException("Antivirus scan could not be completed"))
        .when(malwareScanner).scan(text);

    assertThatThrownBy(() -> reader.read("notes.txt"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Antivirus scan could not be completed");
  }

  @Test
  void reportsMissingObjectsAsNotFound() {
    when(s3.getObject(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().message("missing").build());

    assertThatThrownBy(() -> reader.read("missing.txt"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  private static ResponseInputStream<GetObjectResponse> stream(byte[] bytes) {
    return new ResponseInputStream<>(
        GetObjectResponse.builder().build(),
        AbortableInputStream.create(new ByteArrayInputStream(bytes)));
  }

  private static byte[] pdf(String text) throws IOException {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(50, 700);
        content.showText(text);
        content.endText();
      }
      document.save(output);
      return output.toByteArray();
    }
  }

  private static byte[] pdfWithPages(int pageCount) throws IOException {
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      for (int i = 0; i < pageCount; i++) {
        document.addPage(new PDPage());
      }
      document.save(output);
      return output.toByteArray();
    }
  }
}
