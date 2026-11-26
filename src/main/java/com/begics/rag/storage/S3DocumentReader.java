package com.begics.rag.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.domain.TranscriptionFormats;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
public class S3DocumentReader {
  public static final int MAX_INDEX_DOCUMENT_BYTES = 20 * 1024 * 1024;
  private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  private final S3Client s3;
  private final String bucket;
  private final SpeechTranscription transcription;

  public S3DocumentReader(
      S3Client s3, @Value("${app.s3.bucket}") String bucket, SpeechTranscription transcription) {
    this.s3 = s3;
    this.bucket = bucket;
    this.transcription = transcription;
  }

  public String read(String key) {
    byte[] content;
    try (ResponseInputStream<GetObjectResponse> object = s3.getObject(GetObjectRequest.builder()
        .bucket(bucket)
        .key(key)
        .build())) {
      content = object.readNBytes(MAX_INDEX_DOCUMENT_BYTES + 1);
      if (content.length > MAX_INDEX_DOCUMENT_BYTES) {
        throw tooLargeToIndex();
      }
    } catch (NoSuchKeyException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "S3 object not found", e);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read S3 object", e);
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "S3 object not found", e);
      }
      throw e;
    }
    if (TranscriptionFormats.supports(key)) {
      if (!transcription.available()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Audio/video transcription requires RAG_PROVIDER=infomaniak");
      }
      return transcription.transcribe(key.substring(key.lastIndexOf('/') + 1), content);
    }
    if (isPdf(content) || key.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
      return extractPdfText(content);
    }
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content))
          .toString();
    } catch (CharacterCodingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "S3 object must be UTF-8 text or a readable PDF", e);
    }
  }

  public void validateIndexableSize(String key) {
    try {
      long size = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
      if (size > MAX_INDEX_DOCUMENT_BYTES) {
        throw tooLargeToIndex();
      }
    } catch (NoSuchKeyException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "S3 object not found", exception);
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "S3 object not found", exception);
      }
      throw exception;
    }
  }

  private static ResponseStatusException tooLargeToIndex() {
    return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
        "Only files up to 20 MiB can be indexed.");
  }

  public static boolean isTranscribable(String filename) {
    return TranscriptionFormats.supports(filename);
  }

  private static boolean isPdf(byte[] content) {
    if (content.length < PDF_SIGNATURE.length) return false;
    for (int i = 0; i < PDF_SIGNATURE.length; i++) {
      if (content[i] != PDF_SIGNATURE[i]) return false;
    }
    return true;
  }

  private static String extractPdfText(byte[] content) {
    try (PDDocument document = Loader.loadPDF(content)) {
      return new PDFTextStripper().getText(document);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "S3 object is not a valid readable PDF", e);
    }
  }
}
