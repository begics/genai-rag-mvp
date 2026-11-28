package com.begics.rag.storage;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import com.begics.rag.domain.DocumentOcr;
import com.begics.rag.domain.DocumentVision;
import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.domain.TranscriptionFormats;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
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
  static final int MAX_PDF_PAGES = 200;
  static final int MAX_PDF_EXTRACTED_CHARACTERS = 100_000;
  static final int MAX_OCR_PAGES = 25;
  static final int MAX_VISUAL_REVIEW_PAGES = 5;
  private static final int MIN_PAGE_TEXT_CHARACTERS = 15;
  private static final int MAX_IMAGE_PIXELS = 20_000_000;
  private static final int MAX_VISION_IMAGE_BYTES = 5 * 1024 * 1024;
  private static final int PDF_RENDER_DPI = 150;
  private static final double MIN_OCR_CONFIDENCE = 0.55;
  private static final double MIN_VISUAL_CONFIDENCE = 0.6;
  private static final double MIN_REVIEW_CONFIDENCE = 0.75;
  private static final long MAX_PDF_TEMP_STORAGE_BYTES = 128L * 1024 * 1024;
  private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

  private final S3Client s3;
  private final String bucket;
  private final SpeechTranscription transcription;
  private final FileMalwareScanner malwareScanner;
  private final DocumentOcr ocr;
  private final java.util.function.Supplier<DocumentVision> vision;

  @org.springframework.beans.factory.annotation.Autowired
  public S3DocumentReader(
      S3Client s3, @Value("${app.s3.bucket}") String bucket, SpeechTranscription transcription,
      FileMalwareScanner malwareScanner, DocumentOcr ocr, ObjectProvider<DocumentVision> vision) {
    this(s3, bucket, transcription, malwareScanner, ocr, vision::getIfAvailable);
  }

  S3DocumentReader(
      S3Client s3, String bucket, SpeechTranscription transcription,
      FileMalwareScanner malwareScanner, DocumentOcr ocr,
      java.util.function.Supplier<DocumentVision> vision) {
    this.s3 = s3;
    this.bucket = bucket;
    this.transcription = transcription;
    this.malwareScanner = malwareScanner;
    this.ocr = ocr;
    this.vision = vision;
  }

  public S3DocumentReader(
      S3Client s3, String bucket, SpeechTranscription transcription, FileMalwareScanner malwareScanner) {
    this(s3, bucket, transcription, malwareScanner,
        new TesseractDocumentOcr("tesseract", "eng+deu", 30_000), () -> null);
  }

  public String read(String key) {
    return read(key, ignored -> {});
  }

  public String read(String key, Consumer<ReadStage> stageUpdater) {
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
    malwareScanner.scan(content);
    if (TranscriptionFormats.supports(key)) {
      if (!transcription.available()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Audio/video transcription requires RAG_PROVIDER=infomaniak");
      }
      return transcription.transcribe(key.substring(key.lastIndexOf('/') + 1), content);
    }
    if (isPdf(content) || key.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
      return extractPdfText(content, stageUpdater);
    }
    if (isImageFilename(key)) {
      return extractImageText(content, stageUpdater);
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

  private String extractPdfText(byte[] content, Consumer<ReadStage> stageUpdater) {
    try (PDDocument document = Loader.loadPDF(content, "", null, null,
        MemoryUsageSetting.setupTempFileOnly(MAX_PDF_TEMP_STORAGE_BYTES).streamCache)) {
      if (document.getNumberOfPages() > MAX_PDF_PAGES) {
        throw tooLargePdf();
      }
      StringBuilder output = new StringBuilder();
      int ocrPages = 0;
      int visualPages = 0;
      PDFRenderer renderer = new PDFRenderer(document);
      for (int page = 0; page < document.getNumberOfPages(); page++) {
        String text = extractPageText(document, page);
        if (hasInsufficientText(text)) {
          if (++ocrPages > MAX_OCR_PAGES) {
            throw tooManyOcrPages();
          }
          stageUpdater.accept(ReadStage.OCR);
          byte[] image = renderPage(renderer, document, page);
          DocumentOcr.OcrResult recognized = ocr.recognize(image);
          if (recognized == null) {
            throw new IllegalStateException("Local OCR returned no result");
          }
          text = recognized.text() == null ? "" : recognized.text().strip();
          if (text.isBlank() || !isReliableOcr(recognized.confidence())) {
            if (++visualPages > MAX_VISUAL_REVIEW_PAGES) {
              throw tooManyVisualPages();
            }
            text = escalateLowConfidencePage(
                "image/png", image, text, stageUpdater);
          }
        }
        appendPage(output, text);
      }
      return output.toString();
    } catch (PdfExtractionLimitException exception) {
      throw tooLargePdf();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "S3 object is not a valid readable PDF", e);
    }
  }

  private String extractImageText(byte[] content, Consumer<ReadStage> stageUpdater) {
    String mediaType = validateImage(content);
    stageUpdater.accept(ReadStage.OCR);
    DocumentOcr.OcrResult recognized = ocr.recognize(content);
    if (recognized == null) {
      throw new IllegalStateException("Local OCR returned no result");
    }
    String text = recognized.text() == null ? "" : recognized.text().strip();
    if (text.isBlank() || !isReliableOcr(recognized.confidence())) {
      return escalateLowConfidencePage(mediaType, content, text, stageUpdater);
    }
    if (text.length() > MAX_PDF_EXTRACTED_CHARACTERS) {
      throw tooLargePdf();
    }
    return text;
  }

  private static boolean isReliableOcr(double confidence) {
    return Double.isFinite(confidence) && confidence >= MIN_OCR_CONFIDENCE && confidence <= 1;
  }

  private String escalateLowConfidencePage(
      String mediaType, byte[] image, String ocrText, Consumer<ReadStage> stageUpdater) {
    if (image.length > MAX_VISION_IMAGE_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "Low-confidence OCR image exceeds the 5 MiB local vision limit");
    }
    DocumentVision reader = vision.get();
    if (reader == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Low-confidence OCR requires a configured local vision model");
    }
    stageUpdater.accept(ReadStage.VISUAL_ANALYSIS);
    DocumentVision.VisualText visualText = reader.readOriginal(mediaType, image, ocrText);
    if (visualText.text() == null || visualText.text().isBlank()
        || !Double.isFinite(visualText.confidence())
        || visualText.confidence() < MIN_VISUAL_CONFIDENCE) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "The local vision model could not reliably read this page");
    }
    if (visualText.text().length() > MAX_PDF_EXTRACTED_CHARACTERS) {
      throw tooLargePdf();
    }
    stageUpdater.accept(ReadStage.ORIGINAL_REVIEW);
    DocumentVision.OriginalReview review =
        reader.verifyOriginal(mediaType, image, visualText.text());
    if (review.correctedText() == null || review.correctedText().isBlank()
        || !Double.isFinite(review.confidence()) || !review.consistent()
        || review.confidence() < MIN_REVIEW_CONFIDENCE) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "The local original-document review could not verify the transcription");
    }
    return review.correctedText().strip();
  }

  private static String extractPageText(PDDocument document, int page) throws IOException {
    BoundedStringWriter output = new BoundedStringWriter(MAX_PDF_EXTRACTED_CHARACTERS);
    BoundedPdfTextStripper stripper = new BoundedPdfTextStripper();
    stripper.setStartPage(page + 1);
    stripper.setEndPage(page + 1);
    stripper.writeText(document, output);
    return output.toString().strip();
  }

  private static boolean hasInsufficientText(String text) {
    long meaningfulCharacters = text.codePoints()
        .filter(Character::isLetterOrDigit)
        .count();
    return meaningfulCharacters < MIN_PAGE_TEXT_CHARACTERS;
  }

  private static void appendPage(StringBuilder output, String text) {
    if ((long) output.length() + text.length() + 1 > MAX_PDF_EXTRACTED_CHARACTERS) {
      throw tooLargePdf();
    }
    if (!output.isEmpty()) {
      output.append('\n');
    }
    output.append(text);
  }

  private static byte[] renderPage(PDFRenderer renderer, PDDocument document, int pageIndex) throws IOException {
    PDRectangle page = document.getPage(pageIndex).getCropBox();
    double widthPixels = page.getWidth() * PDF_RENDER_DPI / 72;
    double heightPixels = page.getHeight() * PDF_RENDER_DPI / 72;
    if (widthPixels <= 0 || heightPixels <= 0 || widthPixels * heightPixels > MAX_IMAGE_PIXELS) {
      throw tooLargeImage();
    }
    BufferedImage image = renderer.renderImageWithDPI(pageIndex, PDF_RENDER_DPI, ImageType.RGB);
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      if (!ImageIO.write(image, "png", output)) {
        throw new IllegalStateException("Could not encode a PDF page for OCR");
      }
      return output.toByteArray();
    } finally {
      image.flush();
    }
  }

  private static boolean isImageFilename(String key) {
    String lower = key.toLowerCase(Locale.ROOT);
    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
  }

  private static String validateImage(byte[] content) {
    try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(content))) {
      if (input == null) {
        throw invalidImage();
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw invalidImage();
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > MAX_IMAGE_PIXELS) {
          throw tooLargeImage();
        }
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        return switch (format) {
          case "png" -> "image/png";
          case "jpeg", "jpg" -> "image/jpeg";
          default -> throw invalidImage();
        };
      } finally {
        reader.dispose();
      }
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded image is not readable", exception);
    }
  }

  private static ResponseStatusException tooLargePdf() {
    return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
        "PDF exceeds the extraction limits of 200 pages or 100,000 characters.");
  }

  private static ResponseStatusException tooManyOcrPages() {
    return new ResponseStatusException(
        HttpStatus.PAYLOAD_TOO_LARGE, "PDF exceeds the local OCR limit of 25 scanned pages.");
  }

  private static ResponseStatusException tooManyVisualPages() {
    return new ResponseStatusException(
        HttpStatus.PAYLOAD_TOO_LARGE, "PDF exceeds the local visual-review limit of 5 low-confidence pages.");
  }

  private static ResponseStatusException tooLargeImage() {
    return new ResponseStatusException(
        HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds the 20-megapixel processing limit.");
  }

  private static ResponseStatusException invalidImage() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload must be a readable PNG or JPEG image");
  }

  public enum ReadStage {
    OCR, VISUAL_ANALYSIS, ORIGINAL_REVIEW
  }

  private static final class BoundedStringWriter extends Writer {
    private final StringWriter output = new StringWriter();
    private final int maximumCharacters;

    BoundedStringWriter(int maximumCharacters) {
      this.maximumCharacters = maximumCharacters;
    }

    @Override
    public void write(char[] characters, int offset, int length) throws IOException {
      checkLimit(length);
      output.write(characters, offset, length);
    }

    @Override
    public void write(String text, int offset, int length) throws IOException {
      checkLimit(length);
      output.write(text, offset, length);
    }

    @Override
    public void write(int character) throws IOException {
      checkLimit(1);
      output.write(character);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    @Override
    public String toString() {
      return output.toString();
    }

    private void checkLimit(int incomingCharacters) {
      if ((long) output.getBuffer().length() + incomingCharacters > maximumCharacters) {
        throw new PdfExtractionLimitException();
      }
    }
  }

  private static final class BoundedPdfTextStripper extends PDFTextStripper {
    private int extractedCharacters;

    BoundedPdfTextStripper() throws IOException {}

    @Override
    protected void processTextPosition(TextPosition text) {
      String unicode = text.getUnicode();
      if (unicode != null) {
        extractedCharacters += unicode.length();
        if (extractedCharacters > MAX_PDF_EXTRACTED_CHARACTERS) {
          throw new PdfExtractionLimitException();
        }
      }
      super.processTextPosition(text);
    }
  }

  private static final class PdfExtractionLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
