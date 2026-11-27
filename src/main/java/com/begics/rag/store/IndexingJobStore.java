package com.begics.rag.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IndexingJobStore {
  private static final String SELECT_JOB = """
      select j.id, j.source, j.object_key, j.content, j.status, j.stage, j.chunk_count,
             j.error_message, j.job_type, j.workspace_id, j.review_findings::text,
             j.screened_at, j.submitted_by, j.reviewed_by, j.reviewed_at,
             j.indexed_document_id, j.indexed_at, u.uploaded_by, u.uploaded_at
        from indexing_jobs j
        left join uploaded_objects u on u.object_key = j.source and u.workspace_id = j.workspace_id
      """;
  private final JdbcTemplate jdbc;

  public IndexingJobStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public IndexingJob create(UUID workspaceId, String source, String objectKey, String content) {
    return create(workspaceId, source, objectKey, content, "system");
  }

  public IndexingJob create(
      UUID workspaceId, String source, String objectKey, String content, String submittedBy) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into indexing_jobs(
          id, source, object_key, content, status, stage, workspace_id, submitted_by, review_required)
        values (?, ?, ?, ?, 'QUEUED', 'QUEUED', ?, ?, true)
        """, id, source, objectKey, content, workspaceId, submittedBy);
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
    return jdbc.query(SELECT_JOB + " where j.id = ?", IndexingJobStore::mapJob, id).stream().findFirst();
  }

  public Optional<IndexingJob> get(UUID id, UUID workspaceId) {
    return jdbc.query(SELECT_JOB + " where j.id = ? and j.workspace_id = ?",
        IndexingJobStore::mapJob, id, workspaceId).stream().findFirst();
  }

  public List<IndexingJob> recent(UUID workspaceId) {
    return jdbc.query(SELECT_JOB + """
         where j.workspace_id = ?
           and (j.status = 'AWAITING_APPROVAL'
                or j.id in (select recent.id from indexing_jobs recent
                             where recent.workspace_id = j.workspace_id
                             order by recent.updated_at desc, recent.created_at desc limit 20))
         order by j.updated_at desc, j.created_at desc
        """, IndexingJobStore::mapJob, workspaceId);
  }

  public boolean hasActiveObjectKey(UUID workspaceId, String objectKey) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists (select 1 from indexing_jobs
                        where workspace_id = ? and source = ?
                          and status in ('QUEUED', 'PROCESSING', 'AWAITING_APPROVAL'))
        """, Boolean.class, workspaceId, objectKey));
  }

  public List<UUID> queuedIds() {
    return jdbc.query("select id from indexing_jobs where status = 'QUEUED' order by created_at",
        (rs, row) -> rs.getObject(1, UUID.class));
  }

  public void recoverProcessingJobs() {
    jdbc.update("""
        update indexing_jobs
           set status = 'FAILED',
               error_message = 'Application restarted during Whisper transcription. Re-index the file to retry.',
               object_key = null, content = null, updated_at = now()
         where status = 'PROCESSING' and stage = 'TRANSCRIBING'
        """);
    jdbc.update("""
        update indexing_jobs
           set status = 'QUEUED', stage = 'QUEUED', updated_at = now()
         where status = 'PROCESSING' and stage <> 'TRANSCRIBING'
        """);
  }

  public boolean markProcessing(UUID id) {
    return jdbc.update("""
        update indexing_jobs
           set status = 'PROCESSING', stage = 'READING', updated_at = now(), error_message = null
         where id = ? and status = 'QUEUED'
           and (not review_required or screened_at is null or reviewed_at is not null)
        """, id) == 1;
  }

  public void updateStage(UUID id, Stage stage) {
    if (stage == Stage.QUEUED || stage == Stage.REVIEW_PENDING || stage == Stage.REJECTED
        || stage == Stage.COMPLETED || stage == Stage.FAILED) {
      throw new IllegalArgumentException("Stage is not an active processing stage: " + stage);
    }
    jdbc.update("""
        update indexing_jobs
           set stage = ?, updated_at = now()
         where id = ? and status = 'PROCESSING'
        """, stage.name(), id);
  }

  public void saveScreeningText(UUID id, String text) {
    int updated = jdbc.update("""
        update indexing_jobs set content = ?, updated_at = now()
         where id = ? and status = 'PROCESSING'
        """, text, id);
    if (updated != 1) {
      throw new IllegalStateException("Could not save screening text for job " + id);
    }
  }

  public void awaitApproval(UUID id, String text, String findingsJson) {
    int updated = jdbc.update("""
        update indexing_jobs
           set status = 'AWAITING_APPROVAL', stage = 'REVIEW_PENDING', object_key = null, content = ?,
               review_findings = ?::jsonb, screened_at = now(),
               updated_at = now()
         where id = ? and status = 'PROCESSING' and screened_at is null and reviewed_at is null
           and review_required
        """, text, findingsJson, id);
    if (updated != 1) {
      throw new IllegalStateException("Could not place job into approval review: " + id);
    }
  }

  public boolean approve(UUID id, UUID workspaceId, String reviewer) {
    return jdbc.update("""
        update indexing_jobs
           set status = 'QUEUED', stage = 'QUEUED', reviewed_by = ?, reviewed_at = now(),
               updated_at = now()
         where id = ? and workspace_id = ? and status = 'AWAITING_APPROVAL'
        """, reviewer, id, workspaceId) == 1;
  }

  public boolean reject(UUID id, UUID workspaceId, String reviewer) {
    return jdbc.update("""
        update indexing_jobs
           set status = 'REJECTED', stage = 'REJECTED', content = null, object_key = null,
               reviewed_by = ?, reviewed_at = now(), updated_at = now()
         where id = ? and workspace_id = ? and status = 'AWAITING_APPROVAL'
        """, reviewer, id, workspaceId) == 1;
  }

  public void complete(UUID id, String documentId, int chunks) {
    int updated = jdbc.update("""
        update indexing_jobs
           set status = 'COMPLETED', stage = 'COMPLETED', chunk_count = ?,
               object_key = null, content = null,
               indexed_document_id = case when review_required then ? else null end,
               indexed_at = case when review_required then now() else null end, updated_at = now()
         where id = ? and status = 'PROCESSING'
           and (not review_required or (screened_at is not null and reviewed_at is not null))
        """, chunks, documentId, id);
    if (updated != 1) {
      throw new IllegalStateException("Cannot complete an unapproved or inactive indexing job: " + id);
    }
  }

  public void complete(UUID id, int chunks) {
    complete(id, null, chunks);
  }

  public void fail(UUID id, String message) {
    jdbc.update("""
        update indexing_jobs
           set status = 'FAILED', error_message = ?,
               object_key = null, content = null, updated_at = now()
         where id = ?
        """, safeMessage(message), id);
  }

  public boolean failQueued(UUID id, String message) {
    return jdbc.update("""
        update indexing_jobs
           set status = 'FAILED', stage = 'FAILED', error_message = ?,
               object_key = null, content = null, updated_at = now()
         where id = ? and status = 'QUEUED'
        """, safeMessage(message), id) == 1;
  }

  private static String safeMessage(String message) {
    if (message == null || message.isBlank()) {
      return "Indexing failed; check application logs.";
    }
    String sanitized = message.replaceAll("[\\r\\n\\t]", " ");
    return sanitized.substring(0, Math.min(sanitized.length(), 1_000));
  }

  private static IndexingJob mapJob(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new IndexingJob(
        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
        rs.getString(5), Stage.valueOf(rs.getString(6)), rs.getInt(7), rs.getString(8), rs.getString(9),
        rs.getObject(10, UUID.class), rs.getString(11), instant(rs.getTimestamp(12)),
        rs.getString(13), rs.getString(14), instant(rs.getTimestamp(15)),
        rs.getString(16), instant(rs.getTimestamp(17)), rs.getString(18), instant(rs.getTimestamp(19)));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  public record IndexingJob(
      UUID id, String source, String objectKey, String content, String status, Stage stage, int chunks,
      String errorMessage, String jobType, UUID workspaceId, String reviewFindings, Instant screenedAt,
      String submittedBy, String reviewedBy, Instant reviewedAt,
      String indexedDocumentId, Instant indexedAt, String uploadedBy, Instant uploadedAt) {
    public IndexingJob(
        UUID id, String source, String objectKey, String content, String status, Stage stage, int chunks,
        String errorMessage, String jobType, UUID workspaceId) {
      this(id, source, objectKey, content, status, stage, chunks, errorMessage, jobType, workspaceId,
          "[]", null, null, null, null, null, null, null, null);
    }
  }

  public enum Stage {
    QUEUED, READING, TRANSCRIBING, SCREENING, REVIEW_PENDING, CHUNKING, EMBEDDING, SAVING, REINDEXING,
    COMPLETED, FAILED, REJECTED
  }
}
