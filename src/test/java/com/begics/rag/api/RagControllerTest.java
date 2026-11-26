package com.begics.rag.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.Mockito.verify;

import com.begics.rag.contract.ApiModels.IndexingJobResponse;
import com.begics.rag.service.RagService;
import com.begics.rag.service.IndexingJobService;
import com.begics.rag.service.InvoiceExtractionService;
import com.begics.rag.service.WorkspaceAccessService;
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
  private final InvoiceExtractionService invoiceExtraction = Mockito.mock(InvoiceExtractionService.class);
  private final WorkspaceAccessService workspaces = Mockito.mock(WorkspaceAccessService.class);
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
      new RagController(service, indexingJobs, invoiceExtraction, workspaces))
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
  void rejectsBlankInvoiceExtractionKeys() throws Exception {
    mvc.perform(post("/api/documents/extract-invoice").contentType(MediaType.APPLICATION_JSON)
        .content("{\"key\":\" \"}")).andExpect(status().isBadRequest());
  }

  @Test
  void extractsInvoiceFieldsFromTheResolvedWorkspace() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    when(workspaces.resolve(Mockito.any(), Mockito.any())).thenReturn(workspaceId);
    when(invoiceExtraction.extract("uploads/" + workspaceId + "/invoice.pdf", workspaceId))
        .thenReturn(new com.begics.rag.contract.ApiModels.InvoiceExtractionResponse(
            new com.begics.rag.contract.ApiModels.InvoiceField<>("ACME", "ACME GmbH"),
            new com.begics.rag.contract.ApiModels.InvoiceField<>("INV-123", "INV-123"),
            new com.begics.rag.contract.ApiModels.InvoiceField<>(java.time.LocalDate.parse("2026-03-01"), "2026-03-01"),
            new com.begics.rag.contract.ApiModels.InvoiceField<>(null, null),
            new com.begics.rag.contract.ApiModels.InvoiceField<>("CHF", "CHF"),
            new com.begics.rag.contract.ApiModels.InvoiceField<>(new java.math.BigDecimal("120.50"), "CHF 120.50")));

    mvc.perform(post("/api/documents/extract-invoice").contentType(MediaType.APPLICATION_JSON)
        .content("{\"key\":\"uploads/" + workspaceId + "/invoice.pdf\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supplier.value").value("ACME"))
        .andExpect(jsonPath("$.invoiceNumber.value").value("INV-123"))
        .andExpect(jsonPath("$.dueDate.value").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.total.value").value(120.50));
    verify(invoiceExtraction).extract("uploads/" + workspaceId + "/invoice.pdf", workspaceId);
  }

  @Test
  void exposesTheEmbeddingReindexOperation() throws Exception {
    UUID workspaceId = UUID.randomUUID();
    when(workspaces.resolve(Mockito.any(), Mockito.any())).thenReturn(workspaceId);
    when(indexingJobs.enqueueReembed(workspaceId))
        .thenReturn(new IndexingJobResponse(
            UUID.randomUUID(), "embedding-reindex", "QUEUED", "QUEUED", 0, null, "REEMBED"));
    mvc.perform(post("/api/documents/reindex-embeddings"))
        .andExpect(status().isAccepted());
    verify(indexingJobs).enqueueReembed(workspaceId);
  }

  @RestControllerAdvice
  static class ValidationAdvice {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid() {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid request"));
    }
  }
}
