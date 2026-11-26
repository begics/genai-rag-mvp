package com.begics.rag.service;

import com.begics.rag.contract.ApiModels.StorageFileResponse;
import com.begics.rag.contract.ApiModels.StorageUsageResponse;
import com.begics.rag.domain.StorageLimits;
import com.begics.rag.domain.SpeechTranscription;
import com.begics.rag.storage.S3ObjectWriter;
import com.begics.rag.store.DocumentStore;
import com.begics.rag.store.IndexingJobStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StorageQuotaService {
  private final JdbcTemplate jdbc;
  private final S3ObjectWriter objects;
  private final DocumentStore documents;
  private final IndexingJobStore jobs;
  private final SpeechTranscription transcription;

  public StorageQuotaService(
      JdbcTemplate jdbc, S3ObjectWriter objects, DocumentStore documents, IndexingJobStore jobs,
      SpeechTranscription transcription) {
    this.jdbc = jdbc;
    this.objects = objects;
    this.documents = documents;
    this.jobs = jobs;
    this.transcription = transcription;
  }

  @Transactional
  public void checkS3Upload(String subject, UUID workspaceId, long incomingBytes) {
    lockUser(subject);
    requireOwnedWorkspace(subject, workspaceId);
    long used = s3Usage(ownedWorkspaces(subject));
    if (incomingBytes > StorageLimits.USER_LIMIT_BYTES - used) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
          "Your S3 storage limit is 1 GB. Current usage is " + StorageLimits.formatBytes(used)
              + "; this upload needs " + StorageLimits.formatBytes(incomingBytes)
              + ". Delete old files to make room.");
    }
  }

  public StorageUsageResponse usage(String subject, UUID workspaceId) {
    List<UUID> workspaceIds = ownedWorkspaces(subject);
    requireOwnedWorkspace(subject, workspaceId);
    long s3Bytes = s3Usage(workspaceIds);
    long indexBytes = indexedUsage(subject);
    Map<String, DocumentStore.IndexedDocument> indexed = new HashMap<>();
    for (DocumentStore.IndexedDocument document : documents.listDocuments(workspaceId)) {
      indexed.put(document.source(), document);
    }
    List<StorageFileResponse> files = objects.list("uploads/" + workspaceId + "/").stream()
        .map(object -> {
          DocumentStore.IndexedDocument document = indexed.get(object.key());
          String name = object.key().substring(object.key().lastIndexOf('/') + 1);
          return new StorageFileResponse(object.key(), name, object.sizeBytes(), document != null,
              document != null || isIndexable(name),
              document == null ? 0 : document.chunks(),
              document == null ? 0 : document.indexedBytes());
        })
        .sorted(java.util.Comparator.comparing(StorageFileResponse::filename, String.CASE_INSENSITIVE_ORDER))
        .toList();
    return new StorageUsageResponse(s3Bytes, StorageLimits.USER_LIMIT_BYTES,
        indexBytes, StorageLimits.USER_LIMIT_BYTES, files);
  }

  private boolean isIndexable(String filename) {
    String lower = filename.toLowerCase(java.util.Locale.ROOT);
    return lower.matches(".*\\.(txt|md|csv|pdf)$")
        || (transcription.available() && transcription.supports(filename));
  }

  @Transactional
  public void delete(String subject, UUID workspaceId, String key) {
    WorkspaceAccessService.requireObjectKey(key, workspaceId);
    lockUser(subject);
    requireOwnedWorkspace(subject, workspaceId);
    if (jobs.hasActiveObjectKey(workspaceId, key)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "This file is currently being indexed. Wait for the job to finish, then delete it.");
    }
    documents.deleteBySource(workspaceId, key);
    objects.delete(key);
  }

  private long s3Usage(List<UUID> workspaceIds) {
    long total = 0;
    for (UUID workspaceId : workspaceIds) {
      for (S3ObjectWriter.StoredObject object : objects.list("uploads/" + workspaceId + "/")) {
        total = Math.addExact(total, object.sizeBytes());
      }
    }
    return total;
  }

  private long indexedUsage(String subject) {
    Long bytes = jdbc.queryForObject("""
        select         coalesce(sum(coalesce(pg_column_size(c.content), 0) + coalesce(pg_column_size(c.embedding), 0)
                          + coalesce(pg_column_size(c.search_vector), 0)), 0)
          from chunks c
          join documents d on d.id = c.document_id
          join workspace_members m on m.workspace_id = d.workspace_id and m.role = 'OWNER'
         where m.subject = ?
        """, Long.class, subject);
    return bytes == null ? 0 : bytes;
  }

  private List<UUID> ownedWorkspaces(String subject) {
    return jdbc.query("""
        select workspace_id from workspace_members
         where subject = ? and role = 'OWNER'
         order by workspace_id
        """, (rs, row) -> rs.getObject(1, UUID.class), subject);
  }

  private void requireOwnedWorkspace(String subject, UUID workspaceId) {
    boolean owned = Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists (select 1 from workspace_members
                        where subject = ? and workspace_id = ? and role = 'OWNER')
        """, Boolean.class, subject, workspaceId));
    if (!owned) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace access denied");
    }
  }

  private void lockUser(String subject) {
    jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, subject);
  }
}
