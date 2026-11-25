package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.storage.S3ObjectWriter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

  public DocumentUploadService(S3ObjectWriter objectWriter) {
    this.objectWriter = objectWriter;
  }

  public ApiModels.UploadResponse upload(MultipartFile file) {
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

    String key = "uploads/" + UUID.randomUUID() + "/" + filename;
    objectWriter.write(key, fileType.contentType(), file);
    return new ApiModels.UploadResponse(key, filename, fileType.contentType(), file.getSize(),
        fileType.indexable());
  }

  private static String extensionOf(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private record FileType(String contentType, boolean indexable) {}
}
