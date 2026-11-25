package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.contract.ApiModels.IndexedDocumentResponse;
import com.begics.rag.service.WorkspaceAccessService;
import com.begics.rag.service.WorkspaceAccessService.Workspace;
import com.begics.rag.service.IndexedDocumentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
  private final WorkspaceAccessService workspaces;
  private final IndexedDocumentService documents;

  public WorkspaceController(WorkspaceAccessService workspaces, IndexedDocumentService documents) {
    this.workspaces = workspaces;
    this.documents = documents;
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

  @GetMapping("/documents")
  public List<IndexedDocumentResponse> documents(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace) {
    return documents.list(workspaces.resolve(authentication, workspace));
  }

  @PostMapping("/workspaces")
  @ResponseStatus(HttpStatus.CREATED)
  public Workspace create(Authentication authentication,
      @Valid @RequestBody ApiModels.WorkspaceRequest request) {
    return workspaces.create(authentication, request.name());
  }

  public record UserResponse(String subject, List<String> authorities) {}
}
