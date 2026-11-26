package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

class WorkspaceAccessServiceTest {
  @Test
  void adminsDefaultToPersonalWorkspaceWithoutLegacyMembership() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    WorkspaceAccessService service = new WorkspaceAccessService(jdbc);
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        "admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    UUID workspaceId = service.resolve(authentication, null);

    assertThat(workspaceId).isEqualTo(UUID.nameUUIDFromBytes(
        "rag-personal-workspace:admin-subject".getBytes(StandardCharsets.UTF_8)));
    verify(jdbc).update(anyString(), eq(workspaceId));
    verify(jdbc).update(anyString(), eq(workspaceId), eq("admin-subject"));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void rejectsInvalidAndForeignWorkspaceIds() {
    JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
    WorkspaceAccessService service = new WorkspaceAccessService(jdbc);
    var authentication = UsernamePasswordAuthenticationToken.authenticated(
        "keycloak-subject", "", List.of(new SimpleGrantedAuthority("ROLE_USER")));

    assertThatThrownBy(() -> service.resolve(authentication, "not-a-uuid"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Invalid workspace ID");

    UUID foreignWorkspace = UUID.randomUUID();
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(foreignWorkspace), eq("keycloak-subject")))
        .thenReturn(false);
    assertThatThrownBy(() -> service.resolve(authentication, foreignWorkspace.toString()))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void rejectsObjectKeysOutsideTheResolvedWorkspace() {
    UUID workspaceId = UUID.randomUUID();

    assertThatThrownBy(() -> WorkspaceAccessService.requireObjectKey(
        "uploads/" + UUID.randomUUID() + "/notes.txt", workspaceId))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
