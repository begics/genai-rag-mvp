package com.begics.rag.store;

import com.begics.rag.domain.RetrievedChunk;
import com.begics.rag.domain.StorageLimits;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class JdbcDocumentStore implements DocumentStore {
  private static final int RRF_CONSTANT = 60;
  static final UUID LEGACY_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final JdbcTemplate jdbc;
  public JdbcDocumentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  @Transactional
  public String save(UUID workspaceId, String source, List<String> chunks, List<float[]> embeddings,
      String embeddingModel, UUID documentId) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException("Each chunk must have exactly one embedding");
    }
    String owner = ownerOf(workspaceId);
    if (owner != null) {
      lockUser(owner);
    }
    String existingId = jdbc.query("""
        select id::text from documents where workspace_id = ? and source = ? limit 1
        """, rs -> rs.next() ? rs.getString(1) : null, workspaceId, source);
    String id = existingId != null ? existingId
        : documentId == null ? UUID.randomUUID().toString() : documentId.toString();
    jdbc.update("""
        insert into documents(id, source, workspace_id, document_type) values (?::uuid, ?, ?, ?)
        on conflict (id) do update set source = excluded.source, workspace_id = excluded.workspace_id,
                                        document_type = excluded.document_type
        """, id, source, workspaceId, documentType(source));
    jdbc.update("delete from chunks where document_id = ?::uuid", id);
    for (int i = 0; i < chunks.size(); i++) {
      jdbc.update("""
          insert into chunks(document_id, chunk_no, content, embedding, embedding_model)
          values (?::uuid, ?, ?, ?::halfvec, ?)
          """, id, i, chunks.get(i), vector(embeddings.get(i)), embeddingModel);
    }
    long indexedBytes = owner == null ? 0 : indexedBytesForOwner(owner);
    if (owner != null && indexedBytes > StorageLimits.USER_LIMIT_BYTES) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
          "This operation would increase indexed data to " + StorageLimits.formatBytes(indexedBytes)
              + ", above your 1 GB limit. Delete indexed files to free space, then try again.");
    }
    return id;
  }

  public String save(
      String source, List<String> chunks, List<float[]> embeddings, String embeddingModel, UUID documentId) {
    return save(LEGACY_WORKSPACE_ID, source, chunks, embeddings, embeddingModel, documentId);
  }

  @Override
  public List<IndexedDocument> listDocuments(UUID workspaceId) {
    return jdbc.query("""
        select d.id::text, d.source, count(c.id)::integer,
               coalesce(sum(coalesce(pg_column_size(c.content), 0) + coalesce(pg_column_size(c.embedding), 0)
                            + coalesce(pg_column_size(c.search_vector), 0)), 0)::bigint
          from documents d left join chunks c on c.document_id = d.id
         where d.workspace_id = ?
         group by d.id, d.source, d.created_at
         order by d.created_at desc, d.source
        """, (rs, row) -> new IndexedDocument(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4)),
        workspaceId);
  }

  @Override
  public java.util.Optional<SourceChunk> sourceChunk(UUID workspaceId, UUID documentId, int chunkNumber) {
    return jdbc.query("""
        select d.source, c.content
         from documents d join chunks c on c.document_id = d.id
        where d.id = ? and d.workspace_id = ? and c.chunk_no = ?
        """, rs -> rs.next()
        ? java.util.Optional.of(new SourceChunk(rs.getString(1), rs.getString(2)))
        : java.util.Optional.empty(), documentId, workspaceId, chunkNumber);
  }

  @Override
  @Transactional
  public void deleteBySource(UUID workspaceId, String source) {
    jdbc.update("delete from documents where workspace_id = ? and source = ?", workspaceId, source);
  }

  private String ownerOf(UUID workspaceId) {
    return jdbc.query("""
        select subject from workspace_members where workspace_id = ? and role = 'OWNER' limit 1
        """, rs -> rs.next() ? rs.getString(1) : null, workspaceId);
  }

  private void lockUser(String subject) {
    jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, subject);
  }

  private long indexedBytesForOwner(String subject) {
    Long bytes = jdbc.queryForObject("""
        select coalesce(sum(coalesce(pg_column_size(c.content), 0) + coalesce(pg_column_size(c.embedding), 0)
                            + coalesce(pg_column_size(c.search_vector), 0)), 0)
          from chunks c
          join documents d on d.id = c.document_id
          join workspace_members m on m.workspace_id = d.workspace_id and m.role = 'OWNER'
         where m.subject = ?
        """, Long.class, subject);
    return bytes == null ? 0 : bytes;
  }

  @Override
  public List<RetrievedChunk> search(
      UUID workspaceId, String query, float[] embedding, String embeddingModel, int limit) {
    return search(workspaceId, query, embedding, embeddingModel, limit, new DocumentFilter(null, null));
  }

  @Override
  public List<RetrievedChunk> search(
      UUID workspaceId, String query, float[] embedding, String embeddingModel, int limit,
      DocumentFilter filter) {
    String vectorFilter = "";
    String textFilter = "";
    java.util.ArrayList<Object> vectorArgs = new java.util.ArrayList<>();
    vectorArgs.add(vector(embedding));
    vectorArgs.add(embeddingModel);
    vectorArgs.add(workspaceId);
    java.util.ArrayList<Object> textArgs = new java.util.ArrayList<>();
    textArgs.add(query);
    textArgs.add(query);
    textArgs.add(workspaceId);
    if (filter != null && filter.documentType() != null && !filter.documentType().isBlank()) {
      vectorFilter += " and d.document_type = ?";
      textFilter += " and d.document_type = ?";
      vectorArgs.add(filter.documentType().toLowerCase(java.util.Locale.ROOT));
      textArgs.add(filter.documentType().toLowerCase(java.util.Locale.ROOT));
    }
    if (filter != null && filter.source() != null && !filter.source().isBlank()) {
      vectorFilter += " and d.source = ?";
      textFilter += " and d.source = ?";
      vectorArgs.add(filter.source());
      textArgs.add(filter.source());
    }
    vectorArgs.add(vector(embedding));
    vectorArgs.add(limit);
    textArgs.add(limit);

    List<RetrievedChunk> vectorMatches = jdbc.query("""
        select d.id::text, d.source, c.chunk_no, c.content,
               1 - (c.embedding <=> ?::halfvec) as score
          from chunks c join documents d on d.id = c.document_id
         where c.embedding is not null and c.embedding_model = ? and d.workspace_id = ?
        """ + vectorFilter + """
         order by c.embedding <=> ?::halfvec limit ?
        """, this::mapChunk, vectorArgs.toArray());
    List<RetrievedChunk> textMatches = jdbc.query("""
        select d.id::text, d.source, c.chunk_no, c.content,
               ts_rank_cd(c.search_vector, plainto_tsquery('simple', ?)) as score
          from chunks c join documents d on d.id = c.document_id
         where c.search_vector @@ plainto_tsquery('simple', ?) and d.workspace_id = ?
        """ + textFilter + """
         order by score desc, c.id
         limit ?
        """, this::mapChunk, textArgs.toArray());

    Map<String, RankedChunk> fused = new LinkedHashMap<>();
    addRankedMatches(fused, vectorMatches);
    addRankedMatches(fused, textMatches);
    return fused.values().stream()
        .sorted((left, right) -> Double.compare(right.score(), left.score()))
        .limit(limit)
        .map(RankedChunk::chunk)
        .toList();
  }

  public List<RetrievedChunk> search(String query, float[] embedding, String embeddingModel, int limit) {
    return search(LEGACY_WORKSPACE_ID, query, embedding, embeddingModel, limit);
  }

  @Override
  public List<ChunkToEmbed> findChunksWithStaleEmbeddings(
      UUID workspaceId, String embeddingModel, int limit) {
    return jdbc.query("""
        select c.id, c.content from chunks c join documents d on d.id = c.document_id
         where d.workspace_id = ? and (c.embedding is null or c.embedding_model is distinct from ?)
         order by c.id
         limit ?
        """, (rs, row) -> new ChunkToEmbed(rs.getLong(1), rs.getString(2)), workspaceId, embeddingModel, limit);
  }

  public List<ChunkToEmbed> findChunksWithStaleEmbeddings(String embeddingModel, int limit) {
    return findChunksWithStaleEmbeddings(LEGACY_WORKSPACE_ID, embeddingModel, limit);
  }

  @Override
  @Transactional
  public void updateEmbedding(UUID workspaceId, long chunkId, float[] embedding, String embeddingModel) {
    String owner = ownerOf(workspaceId);
    if (owner != null) {
      lockUser(owner);
    }
    int updated = jdbc.update("""
        update chunks c set embedding = ?::halfvec, embedding_model = ?
          from documents d
         where c.document_id = d.id and d.workspace_id = ? and c.id = ?
        """, vector(embedding), embeddingModel, workspaceId, chunkId);
    if (updated != 1) {
      throw new IllegalStateException("Expected to update one embedding for chunk " + chunkId);
    }
    long indexedBytes = owner == null ? 0 : indexedBytesForOwner(owner);
    if (owner != null && indexedBytes > StorageLimits.USER_LIMIT_BYTES) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
          "Re-indexing would increase indexed data to " + StorageLimits.formatBytes(indexedBytes)
              + ", above your 1 GB limit. Delete indexed files to free space, then try again.");
    }
  }

  public void updateEmbedding(long chunkId, float[] embedding, String embeddingModel) {
    updateEmbedding(LEGACY_WORKSPACE_ID, chunkId, embedding, embeddingModel);
  }

  private static String documentType(String source) {
    String lower = source == null ? "" : source.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(".pdf")) return "pdf";
    if (lower.endsWith(".csv")) return "csv";
    if (lower.endsWith(".md")) return "markdown";
    if (lower.endsWith(".txt")) return "text";
    if (lower.matches(".*\\.(mp3|mp4|ogg|opus|wav|webm|wma|m4a|aac|flac)$")) return "media";
    return "other";
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
