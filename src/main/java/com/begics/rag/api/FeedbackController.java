package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.service.AnswerFeedbackService;
import com.begics.rag.service.WorkspaceAccessService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FeedbackController {
  private final AnswerFeedbackService feedback;
  private final WorkspaceAccessService workspaces;

  public FeedbackController(AnswerFeedbackService feedback, WorkspaceAccessService workspaces) {
    this.feedback = feedback;
    this.workspaces = workspaces;
  }

  @PostMapping("/feedback")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void submit(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.AnswerFeedbackRequest request) {
    UUID workspaceId = workspaces.resolve(authentication, workspace);
    feedback.save(request, workspaceId, authentication.getName());
  }
}
