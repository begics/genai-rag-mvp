package com.begics.rag.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.begics.rag.model.InfomaniakInvoiceExtractor;
import com.begics.rag.model.StructuredChatModel;
import com.begics.rag.service.IndexingJobService;
import com.begics.rag.service.InvoiceExtractionService;
import com.begics.rag.service.RagService;
import com.begics.rag.service.WorkspaceAccessService;
import com.begics.rag.storage.S3DocumentReader;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class InvoiceExtractionApiTest {
  private static final UUID WORKSPACE_ID = UUID.fromString("47cd16f1-b925-4fe9-98a5-9092863cf7cc");
  private static final String DOCUMENT_KEY = "uploads/" + WORKSPACE_ID + "/invoice.txt";
  private static final String DOCUMENT =
      "ACME GmbH invoice INV-123 issued 2026-03-01 due 2026-03-31 total CHF 120.50";
  private static final String VALID_RESPONSE = """
      {
        "supplier":{"value":"ACME GmbH","evidence":"ACME GmbH"},
        "invoiceNumber":{"value":"INV-123","evidence":"INV-123"},
        "invoiceDate":{"value":"2026-03-01","evidence":"2026-03-01"},
        "dueDate":{"value":"2026-03-31","evidence":"2026-03-31"},
        "currency":{"value":"CHF","evidence":"CHF 120.50"},
        "total":{"value":"120.50","evidence":"CHF 120.50"}
      }
      """;

  private final S3DocumentReader documents = Mockito.mock(S3DocumentReader.class);
  private final WorkspaceAccessService workspaces = Mockito.mock(WorkspaceAccessService.class);
  private final StructuredChatModel chat = Mockito.mock(StructuredChatModel.class);
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RagController(
      Mockito.mock(RagService.class),
      Mockito.mock(IndexingJobService.class),
      new InvoiceExtractionService(documents,
          new InfomaniakInvoiceExtractor(chat, JsonMapper.builder().build())),
      workspaces)).build();

  @BeforeEach
  void setUp() {
    when(workspaces.resolve(Mockito.any(), Mockito.any())).thenReturn(WORKSPACE_ID);
    when(documents.read(DOCUMENT_KEY)).thenReturn(DOCUMENT);
    when(chat.answerWithSchema(Mockito.anyString(), Mockito.eq(DOCUMENT), Mockito.anyString()))
        .thenReturn(VALID_RESPONSE);
  }

  @Test
  void extractsAndReturnsValidatedFieldsThroughTheHttpEndpoint() throws Exception {
    mvc.perform(post("/api/documents/extract-invoice")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"key\":\"" + DOCUMENT_KEY + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supplier.value").value("ACME GmbH"))
        .andExpect(jsonPath("$.invoiceNumber.value").value("INV-123"))
        .andExpect(jsonPath("$.invoiceDate.value").value("2026-03-01"))
        .andExpect(jsonPath("$.dueDate.value").value("2026-03-31"))
        .andExpect(jsonPath("$.currency.value").value("CHF"))
        .andExpect(jsonPath("$.total.value").value(120.50))
        .andExpect(jsonPath("$.total.evidence").value("CHF 120.50"));

    verify(documents).read(DOCUMENT_KEY);
    verify(chat).answerWithSchema(Mockito.anyString(), Mockito.eq(DOCUMENT), Mockito.argThat(schema ->
        schema.contains("\"additionalProperties\":false")
            && schema.contains("\"required\":[\"supplier\",\"invoiceNumber\",\"invoiceDate\",\"dueDate\",\"currency\",\"total\"]")));
  }

  @Test
  void returnsBadGatewayWhenProviderEvidenceFailsValidation() throws Exception {
    when(chat.answerWithSchema(Mockito.anyString(), Mockito.eq(DOCUMENT), Mockito.anyString()))
        .thenReturn(VALID_RESPONSE.replace("\"evidence\":\"ACME GmbH\"", "\"evidence\":\"not in source\""));

    mvc.perform(post("/api/documents/extract-invoice")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"key\":\"" + DOCUMENT_KEY + "\"}"))
        .andExpect(status().isBadGateway());
  }
}
