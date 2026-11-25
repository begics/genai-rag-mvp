package com.begics.rag.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;

import com.begics.rag.contract.ApiModels.IndexingJobResponse;
import com.begics.rag.service.RagService;
import com.begics.rag.service.IndexingJobService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import java.util.UUID;
import org.mockito.Mockito;

class RagControllerTest {
  private final RagService service = Mockito.mock(RagService.class);
  private final IndexingJobService indexingJobs = Mockito.mock(IndexingJobService.class);
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RagController(service, indexingJobs))
      .setControllerAdvice(new ValidationAdvice()).build();

  @Test
  void rejectsBlankIngestRequests() throws Exception {
    mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
        .content("{\"source\":\"x\",\"text\":\" \"}")).andExpect(status().isBadRequest());
  }

  @Test
  void rejectsOversizedIngestRequests() throws Exception {
    String oversizedText = "x".repeat(1_000_001);
    mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
        .content("{\"source\":\"x\",\"text\":\"" + oversizedText + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsBlankQueries() throws Exception {
    mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\" \"}")).andExpect(status().isBadRequest());
  }

  @Test
  void rejectsOversizedQueries() throws Exception {
    String oversizedQuestion = "x".repeat(10_001);
    mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"" + oversizedQuestion + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void exposesTheEmbeddingReindexOperation() throws Exception {
    when(indexingJobs.enqueueReembed())
        .thenReturn(new IndexingJobResponse(UUID.randomUUID(), "QUEUED", 0, null));
    mvc.perform(post("/api/documents/reindex-embeddings"))
        .andExpect(status().isAccepted());
    verify(indexingJobs).enqueueReembed();
  }

  @RestControllerAdvice
  static class ValidationAdvice {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid() {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request"));
    }
  }
}
