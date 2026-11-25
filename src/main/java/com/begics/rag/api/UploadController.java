package com.begics.rag.api;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.service.DocumentUploadService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
  private final DocumentUploadService uploadService;

  public UploadController(DocumentUploadService uploadService) {
    this.uploadService = uploadService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiModels.UploadResponse upload(@RequestPart("file") @NotNull MultipartFile file) {
    return uploadService.upload(file);
  }
}
