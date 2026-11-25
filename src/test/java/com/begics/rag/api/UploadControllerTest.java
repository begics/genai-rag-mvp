package com.begics.rag.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.begics.rag.contract.ApiModels.UploadResponse;
import com.begics.rag.service.DocumentUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;

class UploadControllerTest {
  private final DocumentUploadService uploadService = org.mockito.Mockito.mock(DocumentUploadService.class);
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new UploadController(uploadService)).build();

  @Test
  void acceptsMultipartUploadsAndReturnsTheS3ObjectKey() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "manual.pdf", "application/pdf",
        "pdf content".getBytes());
    when(uploadService.upload(any())).thenReturn(
        new UploadResponse("uploads/id/manual.pdf", "manual.pdf", "application/pdf", 11, true));

    mvc.perform(multipart("/api/uploads").file(file))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.key").value("uploads/id/manual.pdf"))
        .andExpect(jsonPath("$.indexable").value(true));

    verify(uploadService).upload(any());
  }
}
