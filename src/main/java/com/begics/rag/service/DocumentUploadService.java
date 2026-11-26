package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.storage.S3ObjectWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentUploadService {
  private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
  private static final Map<String, FileType> ALLOWED_TYPES = Map.ofEntries(
      Map.entry("txt", new FileType("text/plain", true)),
      Map.entry("md", new FileType("text/markdown", true)),
      Map.entry("csv", new FileType("text/csv", true)),
      Map.entry("pdf", new FileType("application/pdf", true)),
      Map.entry("mp4", new FileType("video/mp4", false)),
      Map.entry("mov", new FileType("video/quicktime", false)),
      Map.entry("m4v", new FileType("video/x-m4v", false)),
      Map.entry("webm", new FileType("video/webm", false)),
      Map.entry("mp3", new FileType("audio/mpeg", false)),
      Map.entry("wav", new FileType("audio/wav", false)),
      Map.entry("m4a", new FileType("audio/mp4", false)),
      Map.entry("aac", new FileType("audio/aac", false)),
      Map.entry("ogg", new FileType("audio/ogg", false)),
      Map.entry("flac", new FileType("audio/flac", false)));

  private final S3ObjectWriter objectWriter;
  private final StorageQuotaService storageQuota;

  public DocumentUploadService(S3ObjectWriter objectWriter, StorageQuotaService storageQuota) {
    this.objectWriter = objectWriter;
    this.storageQuota = storageQuota;
  }

  @Transactional
  public ApiModels.UploadResponse upload(MultipartFile file, UUID workspaceId, String subject) {
    if (file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a non-empty file to upload");
    }
    if (file.getSize() > MAX_FILE_BYTES) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Files must be 100 MiB or smaller");
    }

    String requestedFilename = file.getOriginalFilename() == null
        ? "" : file.getOriginalFilename().replace('\\', '/');
    String filename = StringUtils.getFilename(requestedFilename);
    filename = filename == null ? "" : filename
        .replaceAll("[^A-Za-z0-9._ -]", "_")
        .replaceAll("^[. ]+|[. ]+$", "");
    String extension = extensionOf(filename);
    FileType fileType = ALLOWED_TYPES.get(extension);
    if (fileType == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Supported file types are text, PDF, video, and audio files");
    }
    validateContent(file, extension);
    storageQuota.checkS3Upload(subject, workspaceId, file.getSize());

    String key = "uploads/" + workspaceId + "/" + UUID.randomUUID() + "/" + filename;
    objectWriter.write(key, fileType.contentType(), file);
    return new ApiModels.UploadResponse(key, filename, fileType.contentType(), file.getSize(),
        fileType.indexable());
  }

  public ApiModels.UploadResponse upload(MultipartFile file, UUID workspaceId) {
    return upload(file, workspaceId, "legacy-test-subject");
  }

  public ApiModels.UploadResponse upload(MultipartFile file) {
    return upload(file, WorkspaceAccessService.LEGACY_WORKSPACE_ID, "legacy-test-subject");
  }

  public void delete(String key, UUID workspaceId, String subject) {
    storageQuota.delete(subject, workspaceId, key);
  }

  private static String extensionOf(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static void validateContent(MultipartFile file, String extension) {
    try (InputStream input = file.getInputStream()) {
      if (isText(extension)) {
        validateUtf8(input);
      } else {
        byte[] signature = input.readNBytes(12);
        if (!matchesSignature(extension, signature)) {
          throw invalidContent();
        }
      }
    } catch (CharacterCodingException exception) {
      throw invalidContent();
    } catch (IOException exception) {
      throw new IllegalStateException("Failed to inspect uploaded file", exception);
    }
  }

  private static void validateUtf8(InputStream input) throws IOException {
    try (InputStreamReader reader = new InputStreamReader(input,
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT))) {
      char[] buffer = new char[4_096];
      while (reader.read(buffer) != -1) {
        // Read to EOF so malformed UTF-8 anywhere in the file is rejected.
      }
    }
  }

  private static boolean isText(String extension) {
    return extension.equals("txt") || extension.equals("md") || extension.equals("csv");
  }

  private static boolean matchesSignature(String extension, byte[] bytes) {
    return switch (extension) {
      case "pdf" -> startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
      case "mp4", "m4a", "m4v", "mov" -> bytes.length >= 12
          && asciiAt(bytes, 4, "ftyp")
          && (!extension.equals("mov") || asciiAt(bytes, 8, "qt  "));
      case "webm" -> startsWith(bytes, new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3});
      case "wav" -> bytes.length >= 12 && asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WAVE");
      case "ogg" -> asciiAt(bytes, 0, "OggS");
      case "flac" -> asciiAt(bytes, 0, "fLaC");
      case "mp3" -> asciiAt(bytes, 0, "ID3") || hasMpegFrameSync(bytes);
      case "aac" -> hasMpegFrameSync(bytes);
      default -> false;
    };
  }

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (bytes[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean asciiAt(byte[] bytes, int offset, String value) {
    return offset + value.length() <= bytes.length
        && new String(bytes, offset, value.length(), StandardCharsets.US_ASCII).equals(value);
  }

  private static boolean hasMpegFrameSync(byte[] bytes) {
    return bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xe0) == 0xe0;
  }

  private static ResponseStatusException invalidContent() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "File content does not match its supported file type");
  }

  private record FileType(String contentType, boolean indexable) {}
}
