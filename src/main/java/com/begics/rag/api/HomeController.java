package com.begics.rag.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
  private static final String DEMO_HOST = "ai-demo.begic.ch";
  private static final String FILES_LOGIN_RETURN_ATTRIBUTE = "workspaceFilesLoginReturn";

  @GetMapping("/")
  public String home(HttpServletRequest request) {
    if (DEMO_HOST.equalsIgnoreCase(request.getServerName())) {
      return "forward:/demo.html";
    }
    return "forward:/index.html";
  }

  @GetMapping("/files")
  public String files() {
    return "forward:/files.html";
  }

  @GetMapping("/files/sign-in")
  public String signInForFiles(HttpServletRequest request) {
    request.getSession(true).setAttribute(FILES_LOGIN_RETURN_ATTRIBUTE, Boolean.TRUE);
    return "redirect:/oauth2/authorization/keycloak";
  }
}
