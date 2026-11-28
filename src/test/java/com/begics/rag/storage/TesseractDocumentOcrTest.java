package com.begics.rag.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TesseractDocumentOcrTest {
  @Test
  void extractsRecognizedWordsAndAveragesTesseractConfidence() {
    String tsv = """
        level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
        5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t80.0\tSynthetic
        5\t1\t1\t1\t1\t2\t11\t0\t10\t10\t100.0\ttext
        4\t1\t1\t1\t1\t0\t0\t0\t21\t10\t-1\t
        """;

    var result = TesseractDocumentOcr.parseTsv(tsv);

    assertThat(result.text()).isEqualTo("Synthetic text");
    assertThat(result.confidence()).isEqualTo(0.9);
  }

  @Test
  void reportsZeroConfidenceWhenTesseractFindsNoWords() {
    var result = TesseractDocumentOcr.parseTsv(
        "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n");

    assertThat(result.text()).isEmpty();
    assertThat(result.confidence()).isZero();
  }
}
