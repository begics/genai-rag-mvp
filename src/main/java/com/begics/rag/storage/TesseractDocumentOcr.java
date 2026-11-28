package com.begics.rag.storage;

import com.begics.rag.domain.DocumentOcr;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TesseractDocumentOcr implements DocumentOcr {
  private static final int MAX_TSV_BYTES = 5 * 1024 * 1024;
  private static final int MAX_ERROR_BYTES = 64 * 1024;
  private final String command;
  private final String languages;
  private final Duration timeout;

  public TesseractDocumentOcr(
      @Value("${app.ocr.command:tesseract}") String command,
      @Value("${app.ocr.languages:eng+deu}") String languages,
      @Value("${app.ocr.timeout-ms:30000}") long timeoutMs) {
    if (command.isBlank() || languages.isBlank() || timeoutMs < 1) {
      throw new IllegalStateException("OCR command, languages, and timeout must be configured");
    }
    this.command = command;
    this.languages = languages;
    this.timeout = Duration.ofMillis(timeoutMs);
  }

  @Override
  public OcrResult recognize(byte[] image) {
    if (image == null || image.length == 0) {
      throw new IllegalArgumentException("OCR image must not be empty");
    }
    Process process;
    try {
      process = new ProcessBuilder(command, "stdin", "stdout", "-l", languages, "--psm", "3", "tsv").start();
    } catch (IOException exception) {
      throw new IllegalStateException("Local Tesseract OCR could not be started", exception);
    }

    AtomicReference<byte[]> output = new AtomicReference<>();
    AtomicReference<IOException> readFailure = new AtomicReference<>();
    Thread outputReader = Thread.startVirtualThread(
        () -> readBounded(process.getInputStream(), MAX_TSV_BYTES, true, process, output, readFailure));
    Thread errorReader = Thread.startVirtualThread(
        () -> readBounded(process.getErrorStream(), MAX_ERROR_BYTES, false, process, null, readFailure));
    try (OutputStream input = process.getOutputStream()) {
      input.write(image);
    } catch (IOException exception) {
      process.destroyForcibly();
      throw new IllegalStateException("Could not send image to local Tesseract OCR", exception);
    }

    try {
      if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Local Tesseract OCR exceeded its configured timeout");
      }
      outputReader.join(timeout.toMillis());
      errorReader.join(timeout.toMillis());
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for local Tesseract OCR", exception);
    }
    if (outputReader.isAlive() || errorReader.isAlive()) {
      process.destroyForcibly();
      throw new IllegalStateException("Local Tesseract OCR did not close its output streams");
    }
    if (readFailure.get() != null) {
      throw new IllegalStateException("Local Tesseract OCR returned excessive or unreadable output",
          readFailure.get());
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("Local Tesseract OCR exited with status " + process.exitValue());
    }
    return parseTsv(new String(output.get(), StandardCharsets.UTF_8));
  }

  static OcrResult parseTsv(String tsv) {
    List<String> words = new ArrayList<>();
    double confidenceSum = 0;
    int confidenceCount = 0;
    for (String line : tsv.split("\\R")) {
      String[] fields = line.split("\\t", 12);
      if (fields.length < 12 || !fields[0].equals("5") || fields[11].isBlank()) {
        continue;
      }
      try {
        double confidence = Double.parseDouble(fields[10]);
        if (Double.isFinite(confidence) && confidence >= 0 && confidence <= 100) {
          confidenceSum += confidence;
          confidenceCount++;
        }
      } catch (NumberFormatException ignored) {
        continue;
      }
      words.add(fields[11]);
    }
    return new OcrResult(
        String.join(" ", words), confidenceCount == 0 ? 0 : confidenceSum / confidenceCount / 100);
  }

  private static void readBounded(
      InputStream input, int maximumBytes, boolean failOnLimit, Process process,
      AtomicReference<byte[]> result, AtomicReference<IOException> failure) {
    try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8_192];
      int total = 0;
      int count;
      while ((count = input.read(buffer)) != -1) {
        int accepted = Math.min(count, Math.max(0, maximumBytes - total));
        output.write(buffer, 0, accepted);
        total += accepted;
        if (accepted < count && failOnLimit) {
          failure.set(new IOException("Output exceeded " + maximumBytes + " bytes"));
          process.destroyForcibly();
          return;
        }
      }
      if (result != null) {
        result.set(output.toByteArray());
      }
    } catch (IOException exception) {
      failure.compareAndSet(null, exception);
      process.destroyForcibly();
    }
  }
}
