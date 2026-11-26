package com.begics.rag.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
  private static final String DEMO_HOST = "ai-demo.begic.ch";

  @GetMapping("/")
  public String home(HttpServletRequest request) {
    if (DEMO_HOST.equalsIgnoreCase(request.getServerName())) {
      return "forward:/demo.html";
    }
    return "forward:/index.html";
  }
}
