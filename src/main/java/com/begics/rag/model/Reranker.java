package com.begics.rag.model;

import com.begics.rag.domain.RetrievedChunk;
import java.util.List;

public interface Reranker {
  List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int limit);
}
