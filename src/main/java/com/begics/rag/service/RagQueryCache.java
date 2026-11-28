package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

@Component
public final class RagQueryCache {
  private static final int DEFAULT_MAX_ENTRIES = 1_000;
  private static final int DEFAULT_TTL_SECONDS = 300;
  private static final String REDIS_KEY_PREFIX = "rag:query-cache:";
  private static final TypeReference<ApiModels.QueryResponse> QUERY_RESPONSE_TYPE = new TypeReference<>() {};
  private static final Logger log = LoggerFactory.getLogger(RagQueryCache.class);
  private final BoundedCache<String, float[]> embeddings;
  private final BoundedCache<String, ApiModels.QueryResponse> answers;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Duration ttl;

  @Autowired
  public RagQueryCache(
      Environment environment, ObjectProvider<MeterRegistry> meterRegistry,
      StringRedisTemplate redis, ObjectMapper objectMapper) {
    this(
        environment.getProperty("rag.cache.max-entries", Integer.class, DEFAULT_MAX_ENTRIES),
        Duration.ofSeconds(environment.getProperty(
            "rag.cache.ttl-seconds", Integer.class, DEFAULT_TTL_SECONDS)),
        meterRegistry.getIfAvailable(SimpleMeterRegistry::new), redis, objectMapper);
  }

  RagQueryCache(int maxEntries, Duration ttl, MeterRegistry meterRegistry) {
    this(maxEntries, ttl, meterRegistry, null, new ObjectMapper());
  }

  RagQueryCache(
      int maxEntries, Duration ttl, MeterRegistry meterRegistry,
      StringRedisTemplate redis, ObjectMapper objectMapper) {
    if (maxEntries < 1 || maxEntries > 10_000 || ttl == null || ttl.isNegative() || ttl.isZero()
        || ttl.compareTo(Duration.ofHours(1)) > 0 || meterRegistry == null || objectMapper == null) {
      throw new IllegalArgumentException("RAG cache limits must be positive and within their configured bounds");
    }
    this.embeddings = new BoundedCache<>("embedding", maxEntries, ttl, meterRegistry);
    this.answers = new BoundedCache<>("answer", maxEntries, ttl, meterRegistry);
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.ttl = ttl;
  }

  public float[] embedding(String modelId, String text, Supplier<float[]> compute) {
    String key = fingerprint(modelId, text);
    Optional<float[]> cached = embeddings.get(key);
    if (cached.isPresent()) {
      return cached.get().clone();
    }
    Optional<float[]> shared = sharedGet("embedding", key, float[].class);
    if (shared.isPresent()) {
      embeddings.put(key, shared.get().clone());
      count("rag.cache.hits", "embedding", "shared");
      return shared.get().clone();
    }
    count("rag.cache.misses", "embedding", "shared");
    float[] vector = Objects.requireNonNull(compute.get(), "Embedding model returned null");
    embeddings.put(key, vector.clone());
    sharedPut("embedding", key, vector);
    return vector.clone();
  }

  public Optional<ApiModels.QueryResponse> answer(String key) {
    Optional<ApiModels.QueryResponse> cached = answers.get(key);
    if (cached.isPresent()) {
      return cached;
    }
    Optional<ApiModels.QueryResponse> shared = sharedGet("answer", key, QUERY_RESPONSE_TYPE);
    if (shared.isPresent()) {
      ApiModels.QueryResponse copy = copyResponse(shared.get());
      answers.put(key, copy);
      count("rag.cache.hits", "answer", "shared");
      return Optional.of(copy);
    }
    count("rag.cache.misses", "answer", "shared");
    return Optional.empty();
  }

  public void cacheAnswer(String key, ApiModels.QueryResponse response) {
    ApiModels.QueryResponse copy = copyResponse(response);
    answers.put(key, copy);
    sharedPut("answer", key, copy);
  }

  public void purgeExpired() {
    embeddings.purgeExpiredEntries();
    answers.purgeExpiredEntries();
  }

  public String answerKey(
      String workspaceId, String question, List<ApiModels.SourceReference> sources) {
    List<String> parts = new ArrayList<>(2 + sources.size() * 4);
    parts.add(workspaceId);
    parts.add(question);
    for (ApiModels.SourceReference source : sources) {
      parts.add(source.documentId());
      parts.add(source.source());
      parts.add(Integer.toString(source.chunk()));
      parts.add(source.excerpt());
    }
    return fingerprint(parts.toArray(String[]::new));
  }

  int embeddingEntries() {
    return embeddings.size();
  }

  int answerEntries() {
    return answers.size();
  }

  private static String fingerprint(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = Objects.requireNonNull(part).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private <T> Optional<T> sharedGet(String cacheName, String key, Class<T> valueType) {
    if (redis == null) {
      return Optional.empty();
    }
    return readShared(cacheName, key, value -> objectMapper.readValue(value, valueType));
  }

  private <T> Optional<T> sharedGet(
      String cacheName, String key, TypeReference<T> valueType) {
    if (redis == null) {
      return Optional.empty();
    }
    return readShared(cacheName, key, value -> objectMapper.readValue(value, valueType));
  }

  private <T> Optional<T> readShared(String cacheName, String key, JsonReader<T> reader) {
    try {
      String value = redis.opsForValue().get(redisKey(cacheName, key));
      if (value == null) {
        return Optional.empty();
      }
      try {
        return Optional.of(reader.read(value));
      } catch (JacksonException exception) {
        count("rag.cache.errors", cacheName, "shared");
        log.warn("Ignoring malformed shared RAG cache entry for cache={}", cacheName);
        redis.delete(redisKey(cacheName, key));
        return Optional.empty();
      }
    } catch (DataAccessException exception) {
      recordRedisFailure(cacheName, "read");
      return Optional.empty();
    }
  }

  private void sharedPut(String cacheName, String key, Object value) {
    if (redis == null) {
      return;
    }
    try {
      String json = objectMapper.writeValueAsString(value);
      redis.opsForValue().set(redisKey(cacheName, key), json, ttl);
    } catch (JacksonException exception) {
      count("rag.cache.errors", cacheName, "shared");
      log.warn("Could not serialize shared RAG cache entry for cache={}", cacheName);
    } catch (DataAccessException exception) {
      recordRedisFailure(cacheName, "write");
    }
  }

  private void recordRedisFailure(String cacheName, String operation) {
    count("rag.cache.errors", cacheName, "shared");
    log.warn("Shared RAG cache unavailable during {}; continuing with the local cache", operation);
  }

  private void count(String meterName, String cacheName, String tier) {
    Counter.builder(meterName)
        .tag("cache", cacheName)
        .tag("tier", tier)
        .register(embeddings.meters)
        .increment();
  }

  private static String redisKey(String cacheName, String key) {
    return REDIS_KEY_PREFIX + cacheName + ":" + key;
  }

  private static ApiModels.QueryResponse copyResponse(ApiModels.QueryResponse response) {
    return new ApiModels.QueryResponse(response.answer(), List.copyOf(response.sources()));
  }

  @FunctionalInterface
  private interface JsonReader<T> {
    T read(String value) throws JacksonException;
  }

  private static final class BoundedCache<K, V> {
    private final String name;
    private final int maximumEntries;
    private final long ttlNanos;
    private final MeterRegistry meters;
    private final String metricName;
    private final Map<K, Entry<V>> values = new LinkedHashMap<>(16, 0.75f, true);

    BoundedCache(String name, int maximumEntries, Duration ttl, MeterRegistry meters) {
      this.name = name;
      this.maximumEntries = maximumEntries;
      this.ttlNanos = ttl.toNanos();
      this.meters = meters;
      this.metricName = name;
      Gauge.builder("rag.cache.entries", this, BoundedCache::size)
          .tag("cache", name)
          .register(meters);
    }

    synchronized Optional<V> get(K key) {
      purgeExpired();
      Entry<V> entry = values.get(key);
      if (entry == null) {
        count("rag.cache.misses", "local");
        return Optional.empty();
      }
      count("rag.cache.hits", "local");
      return Optional.of(entry.value());
    }

    synchronized void put(K key, V value) {
      purgeExpired();
      values.put(key, new Entry<>(value, System.nanoTime()));
      if (values.size() > maximumEntries) {
        K eldest = values.keySet().iterator().next();
        values.remove(eldest);
        count("rag.cache.evictions", "local");
      }
    }

    synchronized int size() {
      purgeExpired();
      return values.size();
    }

    synchronized void purgeExpiredEntries() {
      purgeExpired();
    }

    private void purgeExpired() {
      long now = System.nanoTime();
      Iterator<Map.Entry<K, Entry<V>>> iterator = values.entrySet().iterator();
      while (iterator.hasNext()) {
        if (now - iterator.next().getValue().createdAtNanos() >= ttlNanos) {
          iterator.remove();
          count("rag.cache.evictions", "local");
        }
      }
    }

    private void count(String meterName, String tier) {
      Counter.builder(meterName)
          .tag("cache", metricName)
          .tag("tier", tier)
          .register(meters)
          .increment();
    }
  }

  private record Entry<V>(V value, long createdAtNanos) {}
}
