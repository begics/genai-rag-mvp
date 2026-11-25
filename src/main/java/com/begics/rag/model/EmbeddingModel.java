package com.begics.rag.model;

import java.util.List;

public interface EmbeddingModel {
  float[] embed(String text);

  default String modelId() {
    return "offline";
  }

  default List<float[]> embedAll(List<String> texts) {
    return texts.stream().map(this::embed).toList();
  }
}
