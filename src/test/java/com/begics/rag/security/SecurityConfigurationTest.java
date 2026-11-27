package com.begics.rag.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.begics.rag.api.CsrfController;
import com.begics.rag.contract.ApiModels.IndexingJobResponse;
import com.begics.rag.contract.ApiModels.QueryRequest;
import com.begics.rag.contract.ApiModels.QueryResponse;
import com.begics.rag.contract.ApiModels.S3IngestRequest;
import com.begics.rag.service.DocumentUploadService;
import com.begics.rag.service.IndexingJobService;
import com.begics.rag.service.InvoiceExtractionService;
import com.begics.rag.service.AnswerFeedbackService;
import com.begics.rag.service.PipelineInfoService;
import com.begics.rag.service.StorageQuotaService;
import com.begics.rag.service.DocumentCompareService;
import com.begics.rag.service.DocumentContentService;
import com.begics.rag.service.AgentService;
import com.begics.rag.service.RagService;
import com.begics.rag.service.WorkspaceAccessService;
import com.begics.rag.contract.ApiModels.IndexedDocumentResponse;
import com.begics.rag.service.IndexedDocumentService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(properties = {
    "spring.security.oauth2.client.registration.keycloak.client-id=test-client",
    "spring.security.oauth2.client.provider.keycloak.authorization-uri=http://localhost:8180/realms/rag/protocol/openid-connect/auth",
    "spring.security.oauth2.client.provider.keycloak.token-uri=http://localhost:8180/realms/rag/protocol/openid-connect/token",
    "spring.security.oauth2.client.provider.keycloak.user-info-uri=http://localhost:8180/realms/rag/protocol/openid-connect/userinfo",
    "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=http://localhost:8180/realms/rag/protocol/openid-connect/certs"
})
@Import({SecurityConfiguration.class, CsrfController.class})
class SecurityConfigurationTest {
  private static final UUID WORKSPACE_ID = UUID.fromString("37cbf0e2-8657-40f8-8933-8161f14a46e1");

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ClientRegistrationRepository clientRegistrations;

  @MockitoBean
  private RagService ragService;

  @MockitoBean
  private DocumentUploadService documentUploadService;

  @MockitoBean
  private IndexingJobService indexingJobService;

  @MockitoBean
  private InvoiceExtractionService invoiceExtractionService;

  @MockitoBean
  private AnswerFeedbackService answerFeedbackService;

  @MockitoBean
  private WorkspaceAccessService workspaces;

  @MockitoBean
  private IndexedDocumentService indexedDocumentService;

  @MockitoBean
  private StorageQuotaService storageQuotaService;

  @MockitoBean
  private DocumentContentService documentContentService;

  @MockitoBean
  private DocumentCompareService documentCompareService;

  @MockitoBean
  private AgentService agentService;

  @MockitoBean
  private PipelineInfoService pipelineInfoService;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @Test
  void rejectsUnauthenticatedApiRequests() throws Exception {
    mvc.perform(withCsrf(post("/api/query")).contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"test\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void requiresAuthenticationForTheUploadAndCsrfEndpoints() throws Exception {
    mvc.perform(withCsrf(post("/api/uploads").contentType(MediaType.MULTIPART_FORM_DATA)))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/csrf")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/documents/" + UUID.randomUUID() + "/content"))
        .andExpect(status().isUnauthorized());
    mvc.perform(withCsrf(post("/api/documents/compare").contentType(MediaType.APPLICATION_JSON)
        .content("{\"firstKey\":\"uploads/a/one.txt\",\"secondKey\":\"uploads/a/two.txt\"}")))
        .andExpect(status().isUnauthorized());
    mvc.perform(withCsrf(post("/api/agent/query").contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"compare two policies\"}")))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/storage")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/pipeline-info")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/feedback/summary")).andExpect(status().isUnauthorized());
  }

  @Test
  void readsIndexedSourceContextByWorkspaceDocumentAndChunk() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(documentContentService.read(WORKSPACE_ID, documentId, 3, "quoted passage"))
        .thenReturn(new com.begics.rag.contract.ApiModels.DocumentContentResponse(
            documentId.toString(), "notes.txt", "quoted passage", 0));

    mvc.perform(get("/api/documents/" + documentId + "/content")
            .param("chunk", "3").param("excerpt", "quoted passage").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.filename").value("notes.txt"))
        .andExpect(jsonPath("$.matchOffset").value(0));
    org.mockito.Mockito.verify(documentContentService).read(WORKSPACE_ID, documentId, 3, "quoted passage");
  }

  @Test
  void comparesWorkspaceDocumentsThroughTheAuthenticatedApi() throws Exception {
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(documentCompareService.compare(any(), eq(WORKSPACE_ID)))
        .thenReturn(new com.begics.rag.contract.ApiModels.DocumentCompareResponse(
            "first.txt", "second.txt", List.of("removed"), List.of("added")));

    mvc.perform(withCsrf(post("/api/documents/compare").with(oidcLogin())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"firstKey\":\"uploads/" + WORKSPACE_ID
                + "/first.txt\",\"secondKey\":\"uploads/" + WORKSPACE_ID + "/second.txt\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.removedParagraphs[0]").value("removed"))
        .andExpect(jsonPath("$.addedParagraphs[0]").value("added"));
  }

  @Test
  void returnsWorkspaceScopedFeedbackSummary() throws Exception {
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(answerFeedbackService.summary(WORKSPACE_ID))
        .thenReturn(new com.begics.rag.contract.ApiModels.AnswerFeedbackSummaryResponse(10, 7, 3, 70.0));

    mvc.perform(get("/api/feedback/summary").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalResponses").value(10))
        .andExpect(jsonPath("$.helpfulResponses").value(7))
        .andExpect(jsonPath("$.unhelpfulResponses").value(3))
        .andExpect(jsonPath("$.helpfulRatePercent").value(70.0));
    org.mockito.Mockito.verify(answerFeedbackService).summary(WORKSPACE_ID);
  }

  @Test
  void runsAgenticQueriesOnlyForAuthenticatedWorkspaceRequests() throws Exception {
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(agentService.query(any(), eq(WORKSPACE_ID)))
        .thenReturn(new com.begics.rag.contract.ApiModels.AgentQueryResponse(
            "Grounded answer", List.of(), List.of(
                new com.begics.rag.contract.ApiModels.AgentStepResponse(
                    "planner", "completed", "Plan the task", "Selected one search query.", 2)),
            null, 6));

    mvc.perform(withCsrf(post("/api/agent/query").with(oidcLogin())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"Where is the policy?\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("Grounded answer"))
        .andExpect(jsonPath("$.steps[0].tool").value("planner"))
        .andExpect(jsonPath("$.maxSteps").value(6));
    org.mockito.Mockito.verify(agentService).query(any(), eq(WORKSPACE_ID));
  }

  @Test
  void servesTheSelfContainedSampleDemoWithoutAuthentication() throws Exception {
    mvc.perform(get("/demo.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Prepared-answer prototype")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(
            "href=\"https://ai-rag.begic.ch/\"")));
    mvc.perform(get("/").header("Host", "ai-demo.begic.ch"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/demo.html"));
    mvc.perform(get("/").header("Host", "ai-rag.begic.ch"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/index.html"));
    mvc.perform(get("/demo.css")).andExpect(status().isOk());
    mvc.perform(get("/demo.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Prepared sample response")));
    mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
  }

  @Test
  void acceptsOidcAuthenticatedRequestsAndAddsSecurityHeaders() throws Exception {
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(ragService.query(any(QueryRequest.class), eq(WORKSPACE_ID)))
        .thenReturn(new QueryResponse("answer", List.of()));

    mvc.perform(withCsrf(post("/api/query")).with(oidcLogin()
            .idToken(token -> token.subject("keycloak-subject"))
            .authorities(new SimpleGrantedAuthority("ROLE_USER")))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"test\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy",
            org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
  }

  @Test
  void addsRestrictivePermissionsPolicy() throws Exception {
    mvc.perform(get("/").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(header().string("Permissions-Policy",
            "camera=(), microphone=(), geolocation=()"));
  }

  @Test
  void listsIndexedDocumentsForTheAuthenticatedWorkspace() throws Exception {
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(indexedDocumentService.list(WORKSPACE_ID))
        .thenReturn(List.of(new IndexedDocumentResponse("document-id", "uploads/workspace/notes.txt", 3)));

    mvc.perform(get("/api/documents").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("document-id"))
        .andExpect(jsonPath("$[0].source").value("uploads/workspace/notes.txt"))
        .andExpect(jsonPath("$[0].chunks").value(3));
  }

  @Test
  void returnsConfiguredAiComponentsToAuthenticatedUsers() throws Exception {
    when(pipelineInfoService.current()).thenReturn(
        new com.begics.rag.contract.ApiModels.PipelineInfoResponse(
            "Infomaniak AI", "Embeddings API", "embedding-model", 3584,
            "Rerank API", "rerank-model", "Chat API", "chat-model",
            "Structured planner", "Search and compare", 3));

    mvc.perform(get("/api/pipeline-info").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provider").value("Infomaniak AI"))
        .andExpect(jsonPath("$.embeddingService").value("Embeddings API"))
        .andExpect(jsonPath("$.embeddingModel").value("embedding-model"))
        .andExpect(jsonPath("$.embeddingDimensions").value(3584))
        .andExpect(jsonPath("$.rerankService").value("Rerank API"))
        .andExpect(jsonPath("$.rerankModel").value("rerank-model"))
        .andExpect(jsonPath("$.chatService").value("Chat API"))
        .andExpect(jsonPath("$.chatModel").value("chat-model"))
        .andExpect(jsonPath("$.agentPlanner").value("Structured planner"))
        .andExpect(jsonPath("$.agentTools").value("Search and compare"))
        .andExpect(jsonPath("$.maxAgentSearches").value(3));
  }

  @Test
  void showsPlannerAndAnswerModelsAsSeparatePipelineComponents() throws Exception {
    String html;
    try (var stream = getClass().getResourceAsStream("/static/index.html")) {
      org.assertj.core.api.Assertions.assertThat(stream).isNotNull();
      html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    org.assertj.core.api.Assertions.assertThat(html)
        .contains("Agent planner · planning only")
        .contains("data-pipeline-model=\"agent-planner\"")
        .contains("Answer &amp; extraction LLM")
        .contains("data-pipeline-model=\"chat\"")
        .contains("the planner model plans; the chat model answers and extracts invoice fields");
  }

  @Test
  void contentSecurityPolicyAllowsTheCurrentInlineUiBlocks() throws Exception {
    var response = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse();
    String policy = response.getHeader("Content-Security-Policy");
    org.assertj.core.api.Assertions.assertThat(policy).isNotNull();
    String html;
    try (var stream = getClass().getResourceAsStream("/static/index.html")) {
      org.assertj.core.api.Assertions.assertThat(stream).isNotNull();
      html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    for (String tag : List.of("script", "style")) {
      Matcher matcher = Pattern.compile("<" + tag + "(?:\\s[^>]*)?>(.*?)</" + tag + ">",
          Pattern.DOTALL).matcher(html);
      org.assertj.core.api.Assertions.assertThat(matcher.find())
          .as("inline %s block exists", tag).isTrue();
      String digest = Base64.getEncoder().encodeToString(
          MessageDigest.getInstance("SHA-256").digest(
              matcher.group(1).getBytes(StandardCharsets.UTF_8)));
      org.assertj.core.api.Assertions.assertThat(policy).contains("'sha256-" + digest + "'");
    }
  }

  @Test
  void rejectsSessionAuthenticatedStateChangesWithoutCsrf() throws Exception {
    mvc.perform(post("/api/query").with(oidcLogin())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"test\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void acceptsBearerApiRequestsWithoutBrowserCsrfTokens() throws Exception {
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(ragService.query(any(QueryRequest.class), eq(WORKSPACE_ID)))
        .thenReturn(new QueryResponse("answer", List.of()));
    Instant now = Instant.now();
    when(jwtDecoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token")
        .header("alg", "none")
        .claim("sub", "agent-sub")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(60))
        .build());

    mvc.perform(post("/api/query").header("Authorization", "Bearer test-token")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"question\":\"test\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void issuesSameSiteCsrfTokensForTheLoggedInBrowser() throws Exception {
    mvc.perform(get("/api/csrf").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty())
        .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
  }

  @Test
  void signsOutThroughKeycloakAndReturnsToTheApplication() throws Exception {
    mvc.perform(withCsrf(post("/logout")).with(oidcLogin()
            .clientRegistration(clientRegistrations.findByRegistrationId("keycloak"))))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location",
            org.hamcrest.Matchers.containsString("/protocol/openid-connect/logout")))
        .andExpect(header().string("Location",
            org.hamcrest.Matchers.containsString("post_logout_redirect_uri")));
  }

  @Test
  void acceptsAuthenticatedWorkspaceScopedIndexingJobs() throws Exception {
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(indexingJobService.enqueueS3(any(S3IngestRequest.class), eq(WORKSPACE_ID)))
        .thenReturn(new IndexingJobResponse(
            UUID.randomUUID(), "uploads/" + WORKSPACE_ID + "/file/notes.txt", "QUEUED", "QUEUED", 0, null, "INDEX"));

    mvc.perform(withCsrf(post("/api/indexing-jobs/s3")).with(oidcLogin()
            .authorities(new SimpleGrantedAuthority("ROLE_USER")))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"key\":\"uploads/" + WORKSPACE_ID + "/file/notes.txt\"}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void listsRecentIndexingJobsForTheAuthenticatedWorkspace() throws Exception {
    when(workspaces.resolve(any(), any())).thenReturn(WORKSPACE_ID);
    when(indexingJobService.recent(WORKSPACE_ID)).thenReturn(List.of(new IndexingJobResponse(
        UUID.randomUUID(), "uploads/" + WORKSPACE_ID + "/notes.pdf",
        "PROCESSING", "CHUNKING", 0, null, "INDEX")));

    mvc.perform(get("/api/indexing-jobs").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("PROCESSING"))
        .andExpect(jsonPath("$[0].stage").value("CHUNKING"))
        .andExpect(jsonPath("$[0].source").value("uploads/" + WORKSPACE_ID + "/notes.pdf"));
  }

  @Test
  void mapsOnlyValidKeycloakRealmRolesToSpringAuthorities() {
    var authorities = new HashSet<GrantedAuthority>();
    SecurityConfiguration.addRealmRoles(authorities, Map.of(
        "roles", List.of("USER", "ADMIN", "invalid role", 17)));

    org.assertj.core.api.Assertions.assertThat(authorities)
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
  }

  private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
    return request.cookie(new Cookie("XSRF-TOKEN", "test-token"))
        .header("X-XSRF-TOKEN", "test-token");
  }
}
