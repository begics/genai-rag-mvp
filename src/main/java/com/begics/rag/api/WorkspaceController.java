package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.service.WorkspaceAccessService;
import com.begics.rag.service.WorkspaceAccessService.Workspace;
import com.begics.rag.service.WorkspaceAccessService.WorkspaceMember;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
  private final WorkspaceAccessService workspaces;

  public WorkspaceController(WorkspaceAccessService workspaces) {
    this.workspaces = workspaces;
  }

  @GetMapping("/me")
  public UserResponse me(Authentication authentication) {
    return new UserResponse(authentication.getName(),
        authentication.getAuthorities().stream().map(authority -> authority.getAuthority()).sorted().toList());
  }

  @GetMapping("/workspaces")
  public List<Workspace> list(Authentication authentication) {
    return workspaces.list(authentication);
  }

  @PostMapping("/workspaces")
  @ResponseStatus(HttpStatus.CREATED)
  public Workspace create(Authentication authentication,
      @Valid @RequestBody ApiModels.WorkspaceRequest request) {
    return workspaces.create(authentication, request.name());
  }

  @GetMapping("/workspaces/{id}/members")
  public List<WorkspaceMember> members(Authentication authentication, @PathVariable UUID id) {
    return workspaces.members(authentication, id);
  }

  @PostMapping("/workspaces/{id}/members")
  @ResponseStatus(HttpStatus.CREATED)
  public WorkspaceMember addMember(Authentication authentication, @PathVariable UUID id,
      @Valid @RequestBody ApiModels.WorkspaceMemberRequest request) {
    return workspaces.addMember(authentication, id, request.subject());
  }

  @DeleteMapping("/workspaces/{id}/members/{subject}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeMember(Authentication authentication, @PathVariable UUID id, @PathVariable String subject) {
    workspaces.removeMember(authentication, id, subject);
  }

  public record UserResponse(String subject, List<String> authorities) {}
}
