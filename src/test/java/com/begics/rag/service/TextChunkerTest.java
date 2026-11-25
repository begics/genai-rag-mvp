package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {
  private final TextChunker chunker = new TextChunker();

  @Test
  void combinesParagraphsAndKeepsChunkSizeBoundedWithBoundaryOverlap() {
    String first = "alpha ".repeat(150).strip();
    String second = "beta ".repeat(150).strip();

    List<String> chunks = chunker.split(first + "\n\n" + second);

    assertThat(chunks).hasSize(2);
    assertThat(chunks).allMatch(chunk -> chunk.length() <= 1_500);
    assertThat(chunks.get(0)).contains(first);
    assertThat(chunks.get(1)).contains("beta");
    assertThat(chunks.get(1)).contains(first.substring(first.length() - 150));
  }

  @Test
  void splitsVeryLongParagraphsWithOverlappingText() {
    String text = "word ".repeat(1_000).strip();

    List<String> chunks = chunker.split(text);

    assertThat(chunks).hasSizeGreaterThan(2);
    assertThat(chunks).allMatch(chunk -> chunk.length() <= 1_500);
    for (int i = 1; i < chunks.size(); i++) {
      assertThat(chunks.get(i - 1).substring(chunks.get(i - 1).length() - 150))
          .isNotEmpty();
      assertThat(chunks.get(i)).contains(chunks.get(i - 1).substring(chunks.get(i - 1).length() - 150));
    }
  }

  @Test
  void ignoresBlankParagraphsAndReturnsNoChunksForWhitespace() {
    assertThat(chunker.split(" \n\n\t ")).isEmpty();
    assertThat(chunker.split("first\n\n\nsecond")).containsExactly("first\n\nsecond");
  }
}
