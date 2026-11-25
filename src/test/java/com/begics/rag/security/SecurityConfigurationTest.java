package com.begics.rag.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.begics.rag.contract.ApiModels.QueryRequest;
import com.begics.rag.contract.ApiModels.QueryResponse;
import com.begics.rag.contract.ApiModels.IngestResponse;
import com.begics.rag.contract.ApiModels.S3IngestRequest;
import com.begics.rag.contract.ApiModels.IndexingJobResponse;
import com.begics.rag.service.DocumentUploadService;
import com.begics.rag.service.IndexingJobService;
import com.begics.rag.service.RagService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = {
    "app.security.username=test-user",
    "app.security.password=test-password"
})
@Import(SecurityConfiguration.class)
class SecurityConfigurationTest {
  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private RagService ragService;

  @MockitoBean
  private DocumentUploadService documentUploadService;

  @MockitoBean
  private IndexingJobService indexingJobService;

  @Test
  void rejectsUnauthenticatedApiRequests() throws Exception {
    mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"test\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsUnauthenticatedUploads() throws Exception {
    mvc.perform(post("/api/uploads").contentType(MediaType.MULTIPART_FORM_DATA))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsUnauthenticatedEmbeddingReindexRequests() throws Exception {
    mvc.perform(post("/api/documents/reindex-embeddings"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void acceptsValidBasicAuthentication() throws Exception {
    when(ragService.query(any(QueryRequest.class))).thenReturn(new QueryResponse("answer", List.of()));

    mvc.perform(post("/api/query").header("Authorization", basicAuthHeader())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"test\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void ingestsS3ObjectsThroughTheAuthenticatedApi() throws Exception {
    when(ragService.ingestFromS3(any(S3IngestRequest.class))).thenReturn(new IngestResponse("doc-1", 1));

    mvc.perform(post("/api/documents/s3").header("Authorization", basicAuthHeader())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"key\":\"notes.txt\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void reindexesEmbeddingsThroughTheAuthenticatedApi() throws Exception {
    when(indexingJobService.enqueueReembed())
        .thenReturn(new IndexingJobResponse(UUID.randomUUID(), "QUEUED", 0, null));

    mvc.perform(post("/api/documents/reindex-embeddings")
        .header("Authorization", basicAuthHeader()))
        .andExpect(status().isAccepted());
  }

  @Test
  void rejectsUnauthenticatedIndexingJobs() throws Exception {
    mvc.perform(post("/api/indexing-jobs/s3").contentType(MediaType.APPLICATION_JSON)
        .content("{\"key\":\"notes.txt\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void acceptsAuthenticatedIndexingJobs() throws Exception {
    when(indexingJobService.enqueueS3(any(S3IngestRequest.class)))
        .thenReturn(new IndexingJobResponse(UUID.randomUUID(), "QUEUED", 0, null));

    mvc.perform(post("/api/indexing-jobs/s3").header("Authorization", basicAuthHeader())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"key\":\"notes.txt\"}"))
        .andExpect(status().isAccepted());
  }

  private static String basicAuthHeader() {
    String credentials = Base64.getEncoder().encodeToString(
        "test-user:test-password".getBytes(StandardCharsets.UTF_8));
    return "Basic " + credentials;
  }
}
