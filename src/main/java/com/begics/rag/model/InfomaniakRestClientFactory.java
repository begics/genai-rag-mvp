package com.begics.rag.model;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class InfomaniakRestClientFactory {
  private final RestClient.Builder builder;
  private final HttpClient httpClient;
  private final Duration maxReadTimeout;

  InfomaniakRestClientFactory(
      RestClient.Builder builder, HttpClient httpClient, Duration maxReadTimeout) {
    this.builder = builder;
    this.httpClient = httpClient;
    this.maxReadTimeout = maxReadTimeout;
  }

  RestClient create(Duration requestTimeout) {
    if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("Provider request timeout must be positive");
    }
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(requestTimeout.compareTo(maxReadTimeout) < 0
        ? requestTimeout : maxReadTimeout);
    return builder.clone().requestFactory(requestFactory).build();
  }
}
