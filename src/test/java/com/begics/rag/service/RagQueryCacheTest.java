package com.begics.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.begics.rag.contract.ApiModels;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagQueryCacheTest {
  @Test
  void boundsEntriesAndKeepsEmbeddingValuesIsolated() {
    RagQueryCache cache = new RagQueryCache(2, Duration.ofMinutes(1), new SimpleMeterRegistry());
    AtomicInteger computeCalls = new AtomicInteger();

    float[] first = cache.embedding("model", "first", () -> {
      computeCalls.incrementAndGet();
      return new float[] {1, 2};
    });
    first[0] = 99;
    cache.embedding("model", "second", () -> new float[] {3});
    assertThat(cache.embedding("model", "first", () -> new float[] {4})).containsExactly(1, 2);
    cache.embedding("model", "third", () -> new float[] {5});

    assertThat(cache.embedding("model", "second", () -> new float[] {6})).containsExactly(6);
    assertThat(computeCalls).hasValue(1);
    assertThat(cache.embeddingEntries()).isEqualTo(2);
  }

  @Test
  void expiresEntriesAfterTheConfiguredTtl() throws InterruptedException {
    RagQueryCache cache = new RagQueryCache(2, Duration.ofMillis(1), new SimpleMeterRegistry());
    cache.embedding("model", "question", () -> new float[] {1});

    Thread.sleep(10);

    assertThat(cache.embedding("model", "question", () -> new float[] {2})).containsExactly(2);
  }

  @Test
  void rejectsInvalidCacheBounds() {
    assertThatThrownBy(() -> new RagQueryCache(0, Duration.ofMinutes(1), new SimpleMeterRegistry()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RagQueryCache(1, Duration.ofHours(2), new SimpleMeterRegistry()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void answerKeysIncludeWorkspaceAndExactCitationIdentity() {
    RagQueryCache cache = new RagQueryCache(2, Duration.ofMinutes(1), new SimpleMeterRegistry());
    ApiModels.SourceReference first = new ApiModels.SourceReference("doc-1", "manual.txt", 0, "evidence");
    ApiModels.SourceReference differentDocument =
        new ApiModels.SourceReference("doc-2", "manual.txt", 0, "evidence");
    String key = cache.answerKey("workspace-1", "question", List.of(first));

    assertThat(cache.answerKey("workspace-2", "question", List.of(first))).isNotEqualTo(key);
    assertThat(cache.answerKey("workspace-1", "question", List.of(differentDocument))).isNotEqualTo(key);
    assertThat(cache.answerKey("workspace-1", "question", List.of(first)))
        .isEqualTo(key);
  }

  @Test
  void sharesEmbeddingsAndGroundedAnswersBetweenCacheInstances() {
    Map<String, String> sharedEntries = new ConcurrentHashMap<>();
    StringRedisTemplate redis = sharedRedis(sharedEntries);
    SimpleMeterRegistry firstMeters = new SimpleMeterRegistry();
    SimpleMeterRegistry secondMeters = new SimpleMeterRegistry();
    RagQueryCache firstInstance =
        new RagQueryCache(10, Duration.ofMinutes(5), firstMeters, redis, new ObjectMapper());
    RagQueryCache secondInstance =
        new RagQueryCache(10, Duration.ofMinutes(5), secondMeters, redis, new ObjectMapper());
    AtomicInteger embeddingCalls = new AtomicInteger();

    firstInstance.embedding("model", "question", () -> {
      embeddingCalls.incrementAndGet();
      return new float[] {1, 2};
    });
    float[] sharedEmbedding = secondInstance.embedding("model", "question", () -> {
      embeddingCalls.incrementAndGet();
      return new float[] {3, 4};
    });
    String answerKey = firstInstance.answerKey("workspace", "question", List.of(
        new ApiModels.SourceReference("doc", "notes.txt", 0, "source evidence")));
    firstInstance.cacheAnswer(answerKey, new ApiModels.QueryResponse("grounded", List.of(
        new ApiModels.SourceReference("doc", "notes.txt", 0, "source evidence"))));
    ApiModels.QueryResponse sharedAnswer = secondInstance.answer(answerKey).orElseThrow();

    assertThat(sharedEmbedding).containsExactly(1, 2);
    assertThat(sharedAnswer.answer()).isEqualTo("grounded");
    assertThat(sharedAnswer.sources()).containsExactly(
        new ApiModels.SourceReference("doc", "notes.txt", 0, "source evidence"));
    assertThat(embeddingCalls).hasValue(1);
    assertThat(secondMeters.get("rag.cache.hits").tag("cache", "embedding").tag("tier", "shared")
        .counter().count()).isEqualTo(1);
    assertThat(secondMeters.get("rag.cache.hits").tag("cache", "answer").tag("tier", "shared")
        .counter().count()).isEqualTo(1);
  }

  @Test
  void keepsQueriesUsableWhenSharedRedisIsUnavailable() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException(
        "cache unavailable"));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    RagQueryCache cache =
        new RagQueryCache(2, Duration.ofMinutes(1), meters, redis, new ObjectMapper());
    AtomicInteger calls = new AtomicInteger();

    assertThat(cache.embedding("model", "question", () -> {
      calls.incrementAndGet();
      return new float[] {1};
    })).containsExactly(1);
    assertThat(cache.embedding("model", "question", () -> {
      calls.incrementAndGet();
      return new float[] {2};
    })).containsExactly(1);

    assertThat(calls).hasValue(1);
    Counter errors = meters.get("rag.cache.errors").tag("cache", "embedding").tag("tier", "shared")
        .counter();
    assertThat(errors.count()).isEqualTo(2);
  }

  private static StringRedisTemplate sharedRedis(Map<String, String> entries) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenAnswer(invocation -> entries.get(invocation.getArgument(0)));
    doAnswer(invocation -> {
      entries.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(values).set(anyString(), anyString(), any(Duration.class));
    return redis;
  }
}
