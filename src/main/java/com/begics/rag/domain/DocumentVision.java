package com.begics.rag.domain;

public interface DocumentVision {
  VisualText readOriginal(String mediaType, byte[] image, String ocrText);

  OriginalReview verifyOriginal(String mediaType, byte[] image, String candidateText);

  record VisualText(String text, double confidence) {}

  record OriginalReview(String correctedText, double confidence, boolean consistent) {}
}
