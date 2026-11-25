package com.begics.rag.store;

import com.begics.rag.domain.RetrievedChunk;
import java.util.List;
import java.util.UUID;

public interface DocumentStore {
  String save(UUID workspaceId, String source, List<String> chunks, List<float[]> embeddings,
      String embeddingModel, UUID documentId);
  List<RetrievedChunk> search(UUID workspaceId, String query, float[] embedding, String embeddingModel, int limit);
  List<ChunkToEmbed> findChunksWithStaleEmbeddings(UUID workspaceId, String embeddingModel, int limit);
  void updateEmbedding(UUID workspaceId, long chunkId, float[] embedding, String embeddingModel);
  record ChunkToEmbed(long id, String content) {}
}
