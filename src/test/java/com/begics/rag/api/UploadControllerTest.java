package com.begics.rag.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.begics.rag.contract.ApiModels.UploadResponse;
import com.begics.rag.service.DocumentUploadService;
import com.begics.rag.service.WorkspaceAccessService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;

class UploadControllerTest {
  private final DocumentUploadService uploadService = org.mockito.Mockito.mock(DocumentUploadService.class);
  private final WorkspaceAccessService workspaces = org.mockito.Mockito.mock(WorkspaceAccessService.class);
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UploadController(uploadService, workspaces)).build();

  @Test
  void acceptsMultipartUploadsAndReturnsTheS3ObjectKey() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "manual.pdf", "application/pdf",
        "pdf content".getBytes());
    UUID workspaceId = UUID.randomUUID();
    when(workspaces.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(workspaceId);
    when(uploadService.upload(any(), org.mockito.ArgumentMatchers.eq(workspaceId),
        org.mockito.ArgumentMatchers.eq("test-subject"))).thenReturn(
        new UploadResponse("uploads/id/manual.pdf", "manual.pdf", "application/pdf", 11, true));

    mvc.perform(multipart("/api/uploads").file(file)
            .principal(new TestingAuthenticationToken("test-subject", "n/a")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value("uploads/id/manual.pdf"))
        .andExpect(jsonPath("$.indexable").value(true));

    verify(uploadService).upload(any(), org.mockito.ArgumentMatchers.eq(workspaceId),
        org.mockito.ArgumentMatchers.eq("test-subject"));
  }
}
