package com.begics.rag.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
  private WorkspaceAccessService workspaces;

  @MockitoBean
  private IndexedDocumentService indexedDocumentService;

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
