package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnswerFeedbackService {
  private final JdbcTemplate jdbc;

  public AnswerFeedbackService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public void save(ApiModels.AnswerFeedbackRequest request, UUID workspaceId, String subject) {
    String sourceIds = request.sourceIds() == null || request.sourceIds().isEmpty()
        ? "[]"
        : "[\"" + request.sourceIds().stream()
            .map(id -> id.replace("\\", "\\\\").replace("\"", "\\\""))
            .reduce((left, right) -> left + "\",\"" + right)
            .orElse("")
            + "\"]";
    jdbc.update("""
        insert into answer_feedback(
          workspace_id, subject, question, answer, helpful, comment, source_ids)
        values (?, ?, ?, ?, ?, ?, ?::jsonb)
        """, workspaceId, subject, request.question(), request.answer(), request.helpful(),
        request.comment(), sourceIds);
  }

  public ApiModels.AnswerFeedbackSummaryResponse summary(UUID workspaceId) {
    return jdbc.queryForObject("""
        select count(*) as total_responses,
               count(*) filter (where helpful) as helpful_responses,
               count(*) filter (where not helpful) as unhelpful_responses,
               coalesce(round(100.0 * count(*) filter (where helpful)
                   / nullif(count(*), 0), 1), 0) as helpful_rate_percent
          from answer_feedback
         where workspace_id = ?
        """, (rs, row) -> new ApiModels.AnswerFeedbackSummaryResponse(
        rs.getLong("total_responses"),
        rs.getLong("helpful_responses"),
        rs.getLong("unhelpful_responses"),
        rs.getDouble("helpful_rate_percent")), workspaceId);
  }
}
