package com.begics.rag.model;

@FunctionalInterface
public interface GroundingVerifier {
  boolean isGrounded(String question, String answer, String context);
}
