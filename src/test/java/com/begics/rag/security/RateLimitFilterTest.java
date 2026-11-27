package com.begics.rag.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
  @Test
  void limitsExpensiveQueriesPerClientAndReturnsRetryAfter() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(
        Clock.fixed(Instant.parse("2026-11-25T18:00:00Z"), ZoneOffset.UTC));
    FilterChain chain = mock(FilterChain.class);

    for (int i = 0; i < 30; i++) {
      filter.doFilter(request("/api/query", "192.0.2.1"), new MockHttpServletResponse(), chain);
    }

    MockHttpServletResponse limited = new MockHttpServletResponse();
    filter.doFilter(request("/api/query", "192.0.2.1"), limited, chain);

    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
    verify(chain, times(30)).doFilter(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void limitsMoreExpensiveAgentRunsToTenPerClientAndMinute() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(
        Clock.fixed(Instant.parse("2026-11-25T18:00:00Z"), ZoneOffset.UTC));
    FilterChain chain = mock(FilterChain.class);

    for (int i = 0; i < 10; i++) {
      filter.doFilter(request("/api/agent/query", "192.0.2.1"),
          new MockHttpServletResponse(), chain);
    }

    MockHttpServletResponse limited = new MockHttpServletResponse();
    filter.doFilter(request("/api/agent/query", "192.0.2.1"), limited, chain);

    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
    verify(chain, times(10)).doFilter(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void tracksClientsIndependentlyAndDoesNotLimitReads() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(
        Clock.fixed(Instant.parse("2026-11-25T18:00:00Z"), ZoneOffset.UTC));
    FilterChain chain = mock(FilterChain.class);

    for (int i = 0; i < 30; i++) {
      filter.doFilter(request("/api/query", "192.0.2.1"), new MockHttpServletResponse(), chain);
    }
    MockHttpServletResponse differentClient = new MockHttpServletResponse();
    filter.doFilter(request("/api/query", "192.0.2.2"), differentClient, chain);
    MockHttpServletResponse readRequest = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/api/indexing-jobs/123"), readRequest, chain);

    assertThat(differentClient.getStatus()).isEqualTo(200);
    assertThat(readRequest.getStatus()).isEqualTo(200);
  }

  @Test
  void enforcesGlobalLimitEvenWhenRequestsUseDifferentPaths() throws Exception {
    RateLimitFilter filter = new RateLimitFilter(
        Clock.fixed(Instant.parse("2026-11-25T18:00:00Z"), ZoneOffset.UTC));
    FilterChain chain = mock(FilterChain.class);

    for (int i = 0; i < 300; i++) {
      filter.doFilter(new MockHttpServletRequest("GET", "/page-" + i),
          new MockHttpServletResponse(), chain);
    }
    MockHttpServletResponse limited = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/another-page"), limited, chain);

    assertThat(limited.getStatus()).isEqualTo(429);
  }

  private static MockHttpServletRequest request(String path, String remoteAddress) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(remoteAddress);
    return request;
  }
}
