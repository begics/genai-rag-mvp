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
        insert into indexing_jobs(id, source, object_key, content, status, stage, workspace_id)
        values (?, ?, ?, ?, 'QUEUED', 'QUEUED', ?)
        """, id, source, objectKey, content, workspaceId);
    return get(id).orElseThrow(() -> new IllegalStateException("Created indexing job could not be loaded"));
  }

  public IndexingJob createReembed(UUID workspaceId) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into indexing_jobs(id, source, job_type, status, stage, workspace_id)
        values (?, 'embedding-reindex', 'REEMBED', 'QUEUED', 'QUEUED', ?)
        """, id, workspaceId);
    return get(id).orElseThrow(() -> new IllegalStateException("Created re-embedding job could not be loaded"));
  }

  public Optional<IndexingJob> get(UUID id) {
    return jdbc.query("""
        select id, source, object_key, content, status, stage, chunk_count, error_message, job_type, workspace_id
          from indexing_jobs
         where id = ?
        """, (rs, row) -> new IndexingJob(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), Stage.valueOf(rs.getString(6)), rs.getInt(7), rs.getString(8), rs.getString(9),
            rs.getObject(10, UUID.class)), id).stream().findFirst();
  }

  public Optional<IndexingJob> get(UUID id, UUID workspaceId) {
    return jdbc.query("""
        select id, source, object_key, content, status, stage, chunk_count, error_message, job_type, workspace_id
          from indexing_jobs
         where id = ? and workspace_id = ?
        """, (rs, row) -> new IndexingJob(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), Stage.valueOf(rs.getString(6)), rs.getInt(7), rs.getString(8), rs.getString(9),
            rs.getObject(10, UUID.class)), id, workspaceId).stream().findFirst();
  }

  public List<IndexingJob> recent(UUID workspaceId) {
    return jdbc.query("""
        select id, source, object_key, content, status, stage, chunk_count, error_message, job_type, workspace_id
          from indexing_jobs
         where workspace_id = ?
         order by updated_at desc, created_at desc
         limit 20
        """, (rs, row) -> new IndexingJob(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), Stage.valueOf(rs.getString(6)), rs.getInt(7), rs.getString(8), rs.getString(9),
            rs.getObject(10, UUID.class)), workspaceId);
  }

  public boolean hasActiveObjectKey(UUID workspaceId, String objectKey) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists (select 1 from indexing_jobs
                        where workspace_id = ? and object_key = ? and status in ('QUEUED', 'PROCESSING'))
        """, Boolean.class, workspaceId, objectKey));
  }

  public List<UUID> queuedIds() {
    return jdbc.query("select id from indexing_jobs where status = 'QUEUED' order by created_at",
        (rs, row) -> rs.getObject(1, UUID.class));
  }

  public void recoverProcessingJobs() {
    jdbc.update("""
        update indexing_jobs
           set status = 'QUEUED', stage = 'QUEUED', updated_at = now()
         where status = 'PROCESSING'
        """);
  }

  public boolean markProcessing(UUID id) {
    return jdbc.update("""
        update indexing_jobs
           set status = 'PROCESSING', stage = 'READING', updated_at = now(), error_message = null
         where id = ? and status = 'QUEUED'
        """, id) == 1;
  }

  public void updateStage(UUID id, Stage stage) {
    if (stage == Stage.QUEUED || stage == Stage.COMPLETED || stage == Stage.FAILED) {
      throw new IllegalArgumentException("Stage is not an active processing stage: " + stage);
    }
    jdbc.update("""
        update indexing_jobs
           set stage = ?, updated_at = now()
         where id = ? and status = 'PROCESSING'
        """, stage.name(), id);
  }

  public void complete(UUID id, int chunks) {
    jdbc.update("""
        update indexing_jobs
           set status = 'COMPLETED', stage = 'COMPLETED', chunk_count = ?,
               object_key = null, content = null, updated_at = now()
         where id = ?
        """, chunks, id);
  }

  public void fail(UUID id, String message) {
    jdbc.update("""
        update indexing_jobs
           set status = 'FAILED', stage = 'FAILED', error_message = ?,
               object_key = null, content = null, updated_at = now()
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
      UUID id, String source, String objectKey, String content, String status, Stage stage, int chunks,
      String errorMessage, String jobType, UUID workspaceId) {}

  public enum Stage {
    QUEUED, READING, CHUNKING, EMBEDDING, SAVING, REINDEXING, COMPLETED, FAILED
  }
}
