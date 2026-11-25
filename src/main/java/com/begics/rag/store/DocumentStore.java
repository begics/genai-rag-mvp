package com.begics.rag.store;

import com.begics.rag.domain.RetrievedChunk;
import java.util.List;
import java.util.UUID;

public interface DocumentStore {
  String save(String source, List<String> chunks, List<float[]> embeddings, String embeddingModel, UUID documentId);
  List<RetrievedChunk> search(String query, float[] embedding, String embeddingModel, int limit);
  List<ChunkToEmbed> findChunksWithStaleEmbeddings(String embeddingModel, int limit);
  void updateEmbedding(long chunkId, float[] embedding, String embeddingModel);
  record ChunkToEmbed(long id, String content) {}
}
