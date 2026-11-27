package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.storage.S3DocumentReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentCompareService {
  private static final int MAX_COMPARE_CHARACTERS = 100_000;

  private final S3DocumentReader reader;

  public DocumentCompareService(S3DocumentReader reader) {
    this.reader = reader;
  }

  public ApiModels.DocumentCompareResponse compare(ApiModels.DocumentCompareRequest request, UUID workspaceId) {
    WorkspaceAccessService.requireObjectKey(request.firstKey(), workspaceId);
    WorkspaceAccessService.requireObjectKey(request.secondKey(), workspaceId);
    if (request.firstKey().equals(request.secondKey())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose two different documents");
    }
    if (!isTextOrPdf(request.firstKey()) || !isTextOrPdf(request.secondKey())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Document comparison supports text, Markdown, CSV, and PDF files");
    }
    String firstText = reader.read(request.firstKey());
    String secondText = reader.read(request.secondKey());
    if (firstText.length() > MAX_COMPARE_CHARACTERS || secondText.length() > MAX_COMPARE_CHARACTERS) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
          "Document comparison is limited to 100,000 extracted characters per document");
    }
    if (firstText.isBlank() || secondText.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Documents must contain extractable text");
    }
    return new ApiModels.DocumentCompareResponse(
        filename(request.firstKey()), filename(request.secondKey()),
        difference(firstText, secondText), difference(secondText, firstText));
  }

  private static List<String> difference(String original, String other) {
    Map<String, Integer> remaining = counts(other);
    List<String> result = new ArrayList<>();
    for (String paragraph : paragraphs(original)) {
      int count = remaining.getOrDefault(paragraph, 0);
      if (count == 0) {
        result.add(paragraph);
      } else {
        remaining.put(paragraph, count - 1);
      }
    }
    return List.copyOf(result);
  }

  private static Map<String, Integer> counts(String text) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String paragraph : paragraphs(text)) {
      counts.merge(paragraph, 1, Integer::sum);
    }
    return counts;
  }

  private static List<String> paragraphs(String text) {
    return java.util.Arrays.stream(text.split("\\R\\s*\\R"))
        .map(String::strip)
        .filter(paragraph -> !paragraph.isEmpty())
        .toList();
  }

  private static String filename(String key) {
    return key.substring(key.lastIndexOf('/') + 1);
  }

  private static boolean isTextOrPdf(String key) {
    String lower = key.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".txt") || lower.endsWith(".md")
        || lower.endsWith(".csv") || lower.endsWith(".pdf");
  }
}
