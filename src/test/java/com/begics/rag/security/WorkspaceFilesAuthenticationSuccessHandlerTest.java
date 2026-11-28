package com.begics.rag.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;

class WorkspaceFilesAuthenticationSuccessHandlerTest {
  private final WorkspaceFilesAuthenticationSuccessHandler handler =
      new WorkspaceFilesAuthenticationSuccessHandler();
  private final TestingAuthenticationToken authentication =
      new TestingAuthenticationToken("subject", "credentials");

  @Test
  void returnsToFilesWhenSignInStartedFromTheFilesPage() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    var session = request.getSession();
    session.setAttribute("workspaceFilesLoginReturn", Boolean.TRUE);
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authentication);

    assertThat(response.getRedirectedUrl()).isEqualTo("/files");
    assertThat(session.getAttribute("workspaceFilesLoginReturn")).isNull();
  }

  @Test
  void preservesTheDefaultLoginDestinationForOtherSignInFlows() throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(request, response, authentication);

    assertThat(response.getRedirectedUrl()).isEqualTo("/");
  }
}
