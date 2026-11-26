package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.service.DocumentUploadService;
import com.begics.rag.service.WorkspaceAccessService;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
  private final DocumentUploadService uploadService;
  private final WorkspaceAccessService workspaces;

  public UploadController(DocumentUploadService uploadService, WorkspaceAccessService workspaces) {
    this.uploadService = uploadService;
    this.workspaces = workspaces;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiModels.UploadResponse upload(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @RequestPart("file") @NotNull MultipartFile file) {
    UUID workspaceId = workspaces.resolve(authentication, workspace);
    return uploadService.upload(file, workspaceId, authentication.getName());
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(Authentication authentication,
      @RequestHeader(name = "X-Workspace-ID", required = false) String workspace,
      @RequestParam("key") String key) {
    var workspaceId = workspaces.resolve(authentication, workspace);
    uploadService.delete(key, workspaceId, authentication.getName());
  }
}
