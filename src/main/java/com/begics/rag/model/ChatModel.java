package com.begics.rag.model;

public interface ChatModel {
  String answer(String question, String context);
}
