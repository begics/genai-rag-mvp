package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

class TextChunkerTest {
  private final TextChunker chunker = new TextChunker();
  private final JTokkitTokenCountEstimator tokenCounter = new JTokkitTokenCountEstimator();

  @Test
  void combinesParagraphsAndKeepsChunksWithinTheTokenLimitWithOverlap() {
    String first = "alpha ".repeat(500).strip();
    String second = "beta ".repeat(500).strip();

    List<String> chunks = chunker.split(first + "\n\n" + second);

    assertThat(chunks).hasSize(2);
    assertThat(chunks).allMatch(chunk -> tokenCounter.estimate(chunk) <= 800);
    assertThat(chunks.get(0)).contains("alpha");
    assertThat(chunks.get(1)).contains("beta");
    assertThat(chunks.get(1)).contains("alpha");
  }

  @Test
  void splitsVeryLongParagraphsWithinTheTokenLimitAndOverlapsAdjacentChunks() {
    String text = "word ".repeat(2_000).strip();

    List<String> chunks = chunker.split(text);

    assertThat(chunks).hasSizeGreaterThan(2);
    assertThat(chunks).allMatch(chunk -> tokenCounter.estimate(chunk) <= 800);
    for (int i = 1; i < chunks.size(); i++) {
      String overlap = chunks.get(i - 1).substring(chunks.get(i - 1).length() - 100);
      assertThat(chunks.get(i)).contains(overlap);
    }
  }

  @Test
  void ignoresBlankParagraphsAndReturnsNoChunksForWhitespace() {
    assertThat(chunker.split(" \n\n\t ")).isEmpty();
    assertThat(chunker.split("first\n\n\nsecond")).containsExactly("first\n\nsecond");
  }
}
