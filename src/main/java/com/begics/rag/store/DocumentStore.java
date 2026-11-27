package com.begics.rag.store;

import com.begics.rag.domain.RetrievedChunk;
import java.util.List;
import java.util.UUID;

public interface DocumentStore {
  String save(UUID workspaceId, String source, List<String> chunks, List<float[]> embeddings,
      String embeddingModel, UUID documentId);
  java.util.Optional<SourceChunk> sourceChunk(UUID workspaceId, UUID documentId, int chunkNumber);
  List<IndexedDocument> listDocuments(UUID workspaceId);
  List<RetrievedChunk> search(UUID workspaceId, String query, float[] embedding, String embeddingModel, int limit);
  List<RetrievedChunk> search(UUID workspaceId, String query, float[] embedding, String embeddingModel,
      int limit, DocumentFilter filter);
  List<ChunkToEmbed> findChunksWithStaleEmbeddings(UUID workspaceId, String embeddingModel, int limit);
  void updateEmbedding(UUID workspaceId, long chunkId, float[] embedding, String embeddingModel);
  void deleteBySource(UUID workspaceId, String source);
  record IndexedDocument(String id, String source, int chunks, long indexedBytes) {}
  record SourceChunk(String source, String content) {}
  record ChunkToEmbed(long id, String content) {}
  record DocumentFilter(String source, String documentType) {}
}
