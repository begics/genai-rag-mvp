package com.begics.rag.domain;

public interface SpeechTranscription {
  boolean available();

  boolean supports(String filename);

  String transcribe(String filename, byte[] content);
}
