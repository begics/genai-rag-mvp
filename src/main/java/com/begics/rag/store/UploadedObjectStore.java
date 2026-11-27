package com.begics.rag.store;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UploadedObjectStore {
  private final JdbcTemplate jdbc;

  public UploadedObjectStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(String key, UUID workspaceId, String uploadedBy) {
    jdbc.update("""
        insert into uploaded_objects(object_key, workspace_id, uploaded_by)
        values (?, ?, ?)
        """, key, workspaceId, uploadedBy);
  }
}
