package com.begics.rag.model;

import org.springframework.core.env.Environment;

final class EmbeddingDimensions {
  private static final int MAX_HALFVEC_HNSW_DIMENSIONS = 4_000;

  private EmbeddingDimensions() {}

  static int configured(Environment environment) {
    int dimensions = environment.getProperty("rag.embedding-dimensions", Integer.class, 1_536);
    if (dimensions <= 0 || dimensions > MAX_HALFVEC_HNSW_DIMENSIONS) {
      throw new IllegalStateException(
          "RAG_EMBEDDING_DIMENSIONS must be between 1 and " + MAX_HALFVEC_HNSW_DIMENSIONS
              + " for the pgvector halfvec HNSW index");
    }
    return dimensions;
  }
}
