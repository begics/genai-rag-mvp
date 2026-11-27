package com.begics.rag.model;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ProviderCallLimiter {
  private final MeterRegistry meters;
  private final int maxConcurrentCalls;
  private final Semaphore permits;

  public ProviderCallLimiter(MeterRegistry meters, int maxConcurrentCalls) {
    if (meters == null || maxConcurrentCalls < 1) {
      throw new IllegalArgumentException("Provider call limiter requires a meter registry and positive limit");
    }
    this.meters = meters;
    this.maxConcurrentCalls = maxConcurrentCalls;
    this.permits = new Semaphore(maxConcurrentCalls);
    Gauge.builder("rag.provider.active", this, ProviderCallLimiter::activeCalls)
        .register(meters);
    Gauge.builder("rag.provider.limit", this, limiter -> limiter.maxConcurrentCalls)
        .register(meters);
  }

  public <T> T call(Operation operation, Supplier<T> action) {
    String operationName = operation.name().toLowerCase(Locale.ROOT);
    if (!permits.tryAcquire()) {
      Counter.builder("rag.provider.rejected")
          .tag("operation", operationName)
          .register(meters)
          .increment();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "AI provider is at its concurrency limit; retry shortly");
    }

    Timer timer = Timer.builder("rag.provider.calls")
        .tag("operation", operationName)
        .register(meters);
    try {
      return timer.record(action);
    } catch (RuntimeException | Error exception) {
      Counter.builder("rag.provider.errors")
          .tag("operation", operationName)
          .register(meters)
          .increment();
      throw exception;
    } finally {
      permits.release();
    }
  }

  private double activeCalls() {
    return maxConcurrentCalls - permits.availablePermits();
  }

  int maxConcurrentCalls() {
    return maxConcurrentCalls;
  }

  public enum Operation {
    CHAT, STRUCTURED_CHAT, EMBEDDING, RERANK,
    TRANSCRIPTION_SUBMIT, TRANSCRIPTION_POLL, TRANSCRIPTION_DOWNLOAD
  }
}
