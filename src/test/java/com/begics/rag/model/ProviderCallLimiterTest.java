package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProviderCallLimiterTest {
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final ProviderCallLimiter limiter = new ProviderCallLimiter(meters, 1);

  @Test
  void rejectsConcurrentCallsAndRecordsTheRejection() {
    String result = limiter.call(ProviderCallLimiter.Operation.CHAT, () -> {
      assertThatThrownBy(() -> limiter.call(ProviderCallLimiter.Operation.EMBEDDING, () -> "unreachable"))
          .isInstanceOf(ResponseStatusException.class)
          .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
          .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      return "answer";
    });

    assertThat(result).isEqualTo("answer");
    assertThat(meters.counter("rag.provider.rejected", "operation", "embedding").count()).isEqualTo(1);
    assertThat(meters.timer("rag.provider.calls", "operation", "chat").count()).isEqualTo(1);
    assertThat(meters.get("rag.provider.active").gauge().value()).isZero();
  }

  @Test
  void recordsProviderFailuresAndReleasesCapacity() {
    IllegalStateException failure = new IllegalStateException("provider failed");

    assertThatThrownBy(() -> limiter.call(ProviderCallLimiter.Operation.RERANK, () -> {
      throw failure;
    })).isSameAs(failure);

    assertThat(meters.counter("rag.provider.errors", "operation", "rerank").count()).isEqualTo(1);
    assertThat(limiter.call(ProviderCallLimiter.Operation.CHAT, () -> "capacity released"))
        .isEqualTo("capacity released");
  }
}
