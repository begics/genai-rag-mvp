package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.service.AgentService;
import com.begics.rag.service.WorkspaceAccessService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
  private final AgentService agent;
  private final WorkspaceAccessService workspaces;

  public AgentController(AgentService agent, WorkspaceAccessService workspaces) {
    this.agent = agent;
    this.workspaces = workspaces;
  }

  @PostMapping("/query")
  public ApiModels.AgentQueryResponse query(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.AgentQueryRequest request) {
    return agent.query(request, workspaces.resolve(authentication, workspace));
  }
}
