package com.begics.rag.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IndexingJobStore {
  private final JdbcTemplate jdbc;

  public IndexingJobStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public IndexingJob create(UUID workspaceId, String source, String objectKey, String content) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into indexing_jobs(id, source, object_key, content, status, workspace_id)
        values (?, ?, ?, ?, 'QUEUED', ?)
        """, id, source, objectKey, content, workspaceId);
    return get(id).orElseThrow(() -> new IllegalStateException("Created indexing job could not be loaded"));
  }

  public IndexingJob createReembed(UUID workspaceId) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into indexing_jobs(id, source, job_type, status, workspace_id)
        values (?, 'embedding-reindex', 'REEMBED', 'QUEUED', ?)
        """, id, workspaceId);
    return get(id).orElseThrow(() -> new IllegalStateException("Created re-embedding job could not be loaded"));
  }

  public Optional<IndexingJob> get(UUID id) {
    return jdbc.query("""
        select id, source, object_key, content, status, chunk_count, error_message, job_type, workspace_id
          from indexing_jobs
         where id = ?
        """, (rs, row) -> new IndexingJob(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8),
            rs.getObject(9, UUID.class)), id).stream().findFirst();
  }

  public Optional<IndexingJob> get(UUID id, UUID workspaceId) {
    return jdbc.query("""
        select id, source, object_key, content, status, chunk_count, error_message, job_type, workspace_id
          from indexing_jobs
         where id = ? and workspace_id = ?
        """, (rs, row) -> new IndexingJob(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8),
            rs.getObject(9, UUID.class)), id, workspaceId).stream().findFirst();
  }

  public List<UUID> queuedIds() {
    return jdbc.query("select id from indexing_jobs where status = 'QUEUED' order by created_at",
        (rs, row) -> rs.getObject(1, UUID.class));
  }

  public void recoverProcessingJobs() {
    jdbc.update("""
        update indexing_jobs
           set status = 'QUEUED', updated_at = now()
         where status = 'PROCESSING'
        """);
  }

  public boolean markProcessing(UUID id) {
    return jdbc.update("""
        update indexing_jobs
           set status = 'PROCESSING', updated_at = now(), error_message = null
         where id = ? and status = 'QUEUED'
        """, id) == 1;
  }

  public void complete(UUID id, int chunks) {
    jdbc.update("""
        update indexing_jobs
           set status = 'COMPLETED', chunk_count = ?, object_key = null, content = null, updated_at = now()
         where id = ?
        """, chunks, id);
  }

  public void fail(UUID id, String message) {
    jdbc.update("""
        update indexing_jobs
           set status = 'FAILED', error_message = ?, object_key = null, content = null, updated_at = now()
         where id = ?
        """, safeMessage(message), id);
  }

  private static String safeMessage(String message) {
    if (message == null || message.isBlank()) {
      return "Indexing failed; check application logs.";
    }
    String sanitized = message.replaceAll("[\\r\\n\\t]", " ");
    return sanitized.substring(0, Math.min(sanitized.length(), 1_000));
  }

  public record IndexingJob(
      UUID id, String source, String objectKey, String content, String status, int chunks, String errorMessage,
      String jobType, UUID workspaceId) {}
}
