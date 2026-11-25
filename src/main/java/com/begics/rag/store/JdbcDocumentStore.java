package com.begics.rag.store;

import com.begics.rag.domain.RetrievedChunk;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcDocumentStore implements DocumentStore {
  private static final int RRF_CONSTANT = 60;
  private final JdbcTemplate jdbc;
  public JdbcDocumentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  @Transactional
  public String save(
      String source, List<String> chunks, List<float[]> embeddings, String embeddingModel, UUID documentId) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException("Each chunk must have exactly one embedding");
    }
    String id = documentId == null ? UUID.randomUUID().toString() : documentId.toString();
    jdbc.update("insert into documents(id, source) values (?::uuid, ?) on conflict (id) do update set source = excluded.source",
        id, source);
    jdbc.update("delete from chunks where document_id = ?::uuid", id);
    for (int i = 0; i < chunks.size(); i++) {
      jdbc.update("""
          insert into chunks(document_id, chunk_no, content, embedding, embedding_model)
          values (?::uuid, ?, ?, ?::halfvec, ?)
          """, id, i, chunks.get(i), vector(embeddings.get(i)), embeddingModel);
    }
    return id;
  }

  @Override
  public List<RetrievedChunk> search(String query, float[] embedding, String embeddingModel, int limit) {
    List<RetrievedChunk> vectorMatches = jdbc.query("""
        select d.id::text, d.source, c.chunk_no, c.content,
               1 - (c.embedding <=> ?::halfvec) as score
          from chunks c join documents d on d.id = c.document_id
         where c.embedding is not null and c.embedding_model = ?
         order by c.embedding <=> ?::halfvec limit ?
        """, this::mapChunk, vector(embedding), embeddingModel, vector(embedding), limit);
    List<RetrievedChunk> textMatches = jdbc.query("""
        select d.id::text, d.source, c.chunk_no, c.content,
               ts_rank_cd(c.search_vector, plainto_tsquery('simple', ?)) as score
          from chunks c join documents d on d.id = c.document_id
         where c.search_vector @@ plainto_tsquery('simple', ?)
         order by score desc, c.id
         limit ?
        """, this::mapChunk, query, query, limit);

    Map<String, RankedChunk> fused = new LinkedHashMap<>();
    addRankedMatches(fused, vectorMatches);
    addRankedMatches(fused, textMatches);
    return fused.values().stream()
        .sorted((left, right) -> Double.compare(right.score(), left.score()))
        .limit(limit)
        .map(RankedChunk::chunk)
        .toList();
  }

  @Override
  public List<ChunkToEmbed> findChunksWithStaleEmbeddings(String embeddingModel, int limit) {
    return jdbc.query("""
        select id, content from chunks
         where embedding is null or embedding_model is distinct from ?
         order by id
         limit ?
        """, (rs, row) -> new ChunkToEmbed(rs.getLong(1), rs.getString(2)), embeddingModel, limit);
  }

  @Override
  public void updateEmbedding(long chunkId, float[] embedding, String embeddingModel) {
    int updated = jdbc.update("update chunks set embedding = ?::halfvec, embedding_model = ? where id = ?",
        vector(embedding), embeddingModel, chunkId);
    if (updated != 1) {
      throw new IllegalStateException("Expected to update one embedding for chunk " + chunkId);
    }
  }

  private RetrievedChunk mapChunk(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new RetrievedChunk(rs.getString(1), rs.getString(2),
        rs.getInt(3), rs.getString(4), rs.getDouble(5));
  }

  private static void addRankedMatches(Map<String, RankedChunk> fused, List<RetrievedChunk> matches) {
    for (int rank = 0; rank < matches.size(); rank++) {
      RetrievedChunk chunk = matches.get(rank);
      String key = chunk.documentId() + ":" + chunk.chunk();
      RankedChunk previous = fused.get(key);
      double score = (previous == null ? 0 : previous.score()) + 1.0 / (RRF_CONSTANT + rank + 1);
      fused.put(key, new RankedChunk(chunk, score));
    }
  }

  private record RankedChunk(RetrievedChunk chunk, double score) {}

  private static String vector(float[] values) {
    StringBuilder result = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) result.append(',');
      result.append(values[i]);
    }
    return result.append(']').toString();
  }
}
