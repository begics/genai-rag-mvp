package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.service.DocumentCompareService;
import com.begics.rag.service.DocumentContentService;
import com.begics.rag.service.WorkspaceAccessService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
  private final DocumentContentService content;
  private final DocumentCompareService comparison;
  private final WorkspaceAccessService workspaces;

  public DocumentController(
      DocumentContentService content, DocumentCompareService comparison, WorkspaceAccessService workspaces) {
    this.content = content;
    this.comparison = comparison;
    this.workspaces = workspaces;
  }

  @GetMapping("/{id}/content")
  public ApiModels.DocumentContentResponse content(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @PathVariable UUID id, @RequestParam int chunk, @RequestParam(required = false) String excerpt) {
    return content.read(workspaces.resolve(authentication, workspace), id, chunk, excerpt);
  }

  @PostMapping("/compare")
  public ApiModels.DocumentCompareResponse compare(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @Valid @RequestBody ApiModels.DocumentCompareRequest request) {
    return comparison.compare(request, workspaces.resolve(authentication, workspace));
  }
}
