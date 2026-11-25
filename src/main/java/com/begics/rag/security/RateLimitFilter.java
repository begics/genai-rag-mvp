package com.begics.rag.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {
  private static final long WINDOW_MILLIS = 60_000;
  private static final int MAX_TRACKED_CLIENTS = 10_000;
  private static final int GLOBAL_LIMIT = 300;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private final AtomicLong requests = new AtomicLong();
  private final Clock clock;

  public RateLimitFilter() {
    this(Clock.systemUTC());
  }

  RateLimitFilter(Clock clock) {
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    int limit = limitFor(request.getMethod(), request.getRequestURI());
    long now = clock.millis();
    long windowStart = now / WINDOW_MILLIS * WINDOW_MILLIS;
    if (requests.incrementAndGet() % 128 == 0 || windows.size() >= MAX_TRACKED_CLIENTS) {
      windows.entrySet().removeIf(entry -> entry.getValue().startMillis() + WINDOW_MILLIS <= now);
    }
    String client = request.getRemoteAddr();
    if (!allow(client + ":all", windowStart, GLOBAL_LIMIT)
        || (limit > 0 && !allow(client + ':' + request.getRequestURI(), windowStart, limit))) {
      response.setHeader("Retry-After", Long.toString(Math.max(
          1, (windowStart + WINDOW_MILLIS - now + 999) / 1000)));
      response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "Too many requests; try again shortly.");
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean allow(String key, long windowStart, int limit) {
    boolean[] allowed = {false};
    windows.compute(key, (ignored, current) -> {
      if (current == null || current.startMillis() != windowStart) {
        allowed[0] = windows.size() < MAX_TRACKED_CLIENTS;
        return allowed[0] ? new Window(windowStart, 1) : current;
      }
      if (current.count() < limit) {
        allowed[0] = true;
        return new Window(windowStart, current.count() + 1);
      }
      return current;
    });
    return allowed[0];
  }

  private static int limitFor(String method, String path) {
    if (!path.startsWith("/api/")) {
      return 0;
    }
    if ("POST".equalsIgnoreCase(method) && "/api/query".equals(path)) {
      return 30;
    }
    if ("POST".equalsIgnoreCase(method) && "/api/documents/reindex-embeddings".equals(path)) {
      return 2;
    }
    if ("POST".equalsIgnoreCase(method) && ("/api/uploads".equals(path)
        || path.equals("/api/indexing-jobs")
        || path.equals("/api/indexing-jobs/s3")
        || path.equals("/api/documents")
        || path.equals("/api/documents/s3"))) {
      return 10;
    }
    if ("GET".equalsIgnoreCase(method) && path.startsWith("/api/indexing-jobs/")) {
      return 120;
    }
    if ("GET".equalsIgnoreCase(method) && "/api/csrf".equals(path)) {
      return 60;
    }
    return 0;
  }

  private record Window(long startMillis, int count) {}
}
