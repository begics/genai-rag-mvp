package com.begics.rag.service;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

@Component
public class TextChunker {
  private final TokenTextSplitter splitter = TokenTextSplitter.builder()
      .withChunkSize(800)
      .withChunkOverlap(100)
      .withMinChunkSizeChars(350)
      .withMinChunkLengthToEmbed(0)
      .withKeepSeparator(true)
      .build();

  public List<String> split(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    String normalized = text.lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .collect(Collectors.joining("\n\n"));
    return splitter.split(Document.builder().text(normalized).build()).stream()
        .map(Document::getText)
        .toList();
  }
}
