package com.begics.rag.domain;

public interface DocumentOcr {
  OcrResult recognize(byte[] image);

  record OcrResult(String text, double confidence) {}
}
