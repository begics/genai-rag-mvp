package com.begics.rag.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TextChunker {
  private static final int MAX_CHUNK_CHARACTERS = 1_500;
  private static final int OVERLAP_CHARACTERS = 200;

  public List<String> split(String text) {
    List<String> paragraphs = text.lines()
        .map(String::strip)
        .filter(paragraph -> !paragraph.isEmpty())
        .toList();
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String paragraph : paragraphs) {
      appendParagraph(paragraph, current, chunks);
    }
    if (!current.isEmpty()) {
      chunks.add(current.toString());
    }
    return chunks;
  }

  private static void appendParagraph(String paragraph, StringBuilder current, List<String> chunks) {
    if (paragraph.length() > MAX_CHUNK_CHARACTERS) {
      if (!current.isEmpty()) {
        chunks.add(current.toString());
        current.setLength(0);
      }
      appendLongParagraph(paragraph, chunks);
      return;
    }
    int separator = current.isEmpty() ? 0 : 2;
    if (current.length() + separator + paragraph.length() > MAX_CHUNK_CHARACTERS) {
      String previous = current.toString();
      chunks.add(previous);
      current.setLength(0);
      String overlap = trailingOverlap(previous);
      if (overlap.length() + paragraph.length() + 2 <= MAX_CHUNK_CHARACTERS) {
        current.append(overlap).append("\n\n");
      }
    } else if (separator > 0) {
      current.append("\n\n");
    }
    current.append(paragraph);
  }

  private static void appendLongParagraph(String paragraph, List<String> chunks) {
    int start = 0;
    while (start < paragraph.length()) {
      int end = Math.min(start + MAX_CHUNK_CHARACTERS, paragraph.length());
      if (end < paragraph.length()) {
        int boundary = paragraph.lastIndexOf(' ', end);
        if (boundary > start + MAX_CHUNK_CHARACTERS / 2) {
          end = boundary;
        }
      }
      chunks.add(paragraph.substring(start, end).strip());
      if (end == paragraph.length()) {
        break;
      }
      int overlapStart = Math.max(start, end - OVERLAP_CHARACTERS);
      int boundary = paragraph.indexOf(' ', overlapStart);
      start = boundary >= 0 && boundary < end ? boundary + 1 : overlapStart;
    }
  }

  private static String trailingOverlap(String text) {
    int start = Math.max(0, text.length() - OVERLAP_CHARACTERS);
    int boundary = text.indexOf(' ', start);
    if (boundary >= 0 && boundary < text.length() - 1) {
      start = boundary + 1;
    }
    return text.substring(start);
  }
}
