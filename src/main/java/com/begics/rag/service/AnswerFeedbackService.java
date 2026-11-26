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
}
