package com.begics.rag.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceAccessService {
  public static final UUID LEGACY_WORKSPACE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final JdbcTemplate jdbc;

  public WorkspaceAccessService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public UUID resolve(Authentication authentication, String requestedWorkspace) {
    String subject = requireSubject(authentication);
    ensurePersonalWorkspace(subject);
    if (isAdmin(authentication)) {
      jdbc.update("""
          insert into workspace_members(workspace_id, subject, role) values (?, ?, 'OWNER')
          on conflict (workspace_id, subject) do nothing
          """, LEGACY_WORKSPACE_ID, subject);
    }
    if (requestedWorkspace == null || requestedWorkspace.isBlank()) {
      return personalWorkspaceId(subject);
    }
    UUID workspaceId;
    try {
      workspaceId = UUID.fromString(requestedWorkspace);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workspace ID");
    }
    if (!isMember(workspaceId, subject)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace access denied");
    }
    return workspaceId;
  }

  @Transactional
  public List<Workspace> list(Authentication authentication) {
    String subject = requireSubject(authentication);
    ensurePersonalWorkspace(subject);
    resolve(authentication, null);
    return jdbc.query("""
        select w.id, w.name, m.role from workspaces w
          join workspace_members m on m.workspace_id = w.id
         where m.subject = ? order by w.created_at, w.name
        """, (rs, row) -> new Workspace(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), subject);
  }

  @Transactional
  public Workspace create(Authentication authentication, String name) {
    String subject = requireSubject(authentication);
    UUID id = UUID.randomUUID();
    jdbc.update("insert into workspaces(id, name) values (?, ?)", id, name.trim());
    jdbc.update("insert into workspace_members(workspace_id, subject, role) values (?, ?, 'OWNER')",
        id, subject);
    return new Workspace(id, name.trim(), "OWNER");
  }

  @Transactional
  public List<WorkspaceMember> members(Authentication authentication, UUID workspaceId) {
    String subject = requireSubject(authentication);
    requireOwner(workspaceId, subject);
    return jdbc.query("""
        select subject, role from workspace_members where workspace_id = ? order by created_at
        """, (rs, row) -> new WorkspaceMember(rs.getString(1), rs.getString(2)), workspaceId);
  }

  @Transactional
  public WorkspaceMember addMember(
      Authentication authentication, UUID workspaceId, String newSubject) {
    String subject = requireSubject(authentication);
    requireOwner(workspaceId, subject);
    String memberSubject = newSubject == null ? "" : newSubject.trim();
    if (memberSubject.isBlank() || memberSubject.length() > 200 || memberSubject.equals(subject)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide another valid Keycloak user ID");
    }
    jdbc.update("""
        insert into workspace_members(workspace_id, subject, role) values (?, ?, 'MEMBER')
        on conflict (workspace_id, subject) do nothing
        """, workspaceId, memberSubject);
    return jdbc.queryForObject("""
        select subject, role from workspace_members where workspace_id = ? and subject = ?
        """, (rs, row) -> new WorkspaceMember(rs.getString(1), rs.getString(2)),
        workspaceId, memberSubject);
  }

  @Transactional
  public void removeMember(Authentication authentication, UUID workspaceId, String memberSubject) {
    String subject = requireSubject(authentication);
    requireOwner(workspaceId, subject);
    int removed = jdbc.update("""
        delete from workspace_members
         where workspace_id = ? and subject = ? and role = 'MEMBER'
        """, workspaceId, memberSubject);
    if (removed == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace member not found");
    }
  }

  public static boolean isAdmin(Authentication authentication) {
    return authentication != null && authentication.getAuthorities().stream()
        .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
  }

  public static void requireObjectKey(String key, UUID workspaceId) {
    if (key == null || !key.startsWith("uploads/" + workspaceId + "/")) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "S3 object not found in this workspace");
    }
  }

  private void ensurePersonalWorkspace(String subject) {
    UUID id = personalWorkspaceId(subject);
    jdbc.update("""
        insert into workspaces(id, name) values (?, 'Personal workspace') on conflict (id) do nothing
        """, id);
    jdbc.update("""
        insert into workspace_members(workspace_id, subject, role) values (?, ?, 'OWNER')
        on conflict (workspace_id, subject) do nothing
        """, id, subject);
  }

  private UUID personalWorkspaceId(String subject) {
    return UUID.nameUUIDFromBytes(("rag-personal-workspace:" + subject).getBytes(StandardCharsets.UTF_8));
  }

  private boolean isMember(UUID workspaceId, String subject) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists (select 1 from workspace_members where workspace_id = ? and subject = ?)
        """, Boolean.class, workspaceId, subject));
  }

  private void requireOwner(UUID workspaceId, String subject) {
    Boolean owner = jdbc.queryForObject("""
        select exists (select 1 from workspace_members
                        where workspace_id = ? and subject = ? and role = 'OWNER')
        """, Boolean.class, workspaceId, subject);
    if (!Boolean.TRUE.equals(owner)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace owner access required");
    }
  }

  private static String requireSubject(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()
        || authentication.getName() == null || authentication.getName().isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    return authentication.getName();
  }

  public record Workspace(UUID id, String name, String role) {}
  public record WorkspaceMember(String subject, String role) {}
}
