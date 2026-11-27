package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.begics.rag.contract.ApiModels;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class AnswerFeedbackServiceTest {
  @Test
  void summarizesOnlyFeedbackFromTheRequestedWorkspace() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    AnswerFeedbackService service = new AnswerFeedbackService(jdbc);
    UUID workspaceId = UUID.randomUUID();
    ApiModels.AnswerFeedbackSummaryResponse expected =
        new ApiModels.AnswerFeedbackSummaryResponse(10, 7, 3, 70.0);
    when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class), eq(workspaceId)))
        .thenReturn(expected);

    assertThat(service.summary(workspaceId)).isEqualTo(expected);
    verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.contains("where workspace_id = ?"),
        org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class), eq(workspaceId));
  }

  @Test
  void persistsWorkspaceScopedAnswerFeedback() {
    JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    AnswerFeedbackService service = new AnswerFeedbackService(jdbc);
    UUID workspaceId = UUID.randomUUID();
    ApiModels.AnswerFeedbackRequest request = new ApiModels.AnswerFeedbackRequest(
        "Which value is correct?",
        "The value is 17.",
        true,
        "Clear answer",
        List.of("document-id"));

    service.save(request, workspaceId, "keycloak-subject");

    verify(jdbc).update(org.mockito.ArgumentMatchers.anyString(),
        eq(workspaceId), eq("keycloak-subject"), eq("Which value is correct?"),
        eq("The value is 17."), eq(true), eq("Clear answer"), eq("[\"document-id\"]"));
  }
}
