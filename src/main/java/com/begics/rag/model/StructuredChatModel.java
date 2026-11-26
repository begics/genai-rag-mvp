package com.begics.rag.model;

public interface StructuredChatModel extends ChatModel {
  String answerWithSchema(String instructions, String context, String jsonSchema);
}
