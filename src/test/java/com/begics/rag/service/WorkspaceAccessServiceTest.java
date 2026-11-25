package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

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
}
