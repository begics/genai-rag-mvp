package com.begics.rag.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

public class WorkspaceFilesAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
  private static final String FILES_LOGIN_RETURN_ATTRIBUTE = "workspaceFilesLoginReturn";
  private final AuthenticationSuccessHandler defaultHandler = new SavedRequestAwareAuthenticationSuccessHandler();

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    var session = request.getSession(false);
    if (session != null && Boolean.TRUE.equals(session.getAttribute(FILES_LOGIN_RETURN_ATTRIBUTE))) {
      session.removeAttribute(FILES_LOGIN_RETURN_ATTRIBUTE);
      response.sendRedirect(request.getContextPath() + "/files");
      return;
    }
    defaultHandler.onAuthenticationSuccess(request, response, authentication);
  }
}
