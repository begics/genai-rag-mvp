package com.begics.rag.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class IndexingConfiguration {
  @Bean("indexingTaskExecutor")
  ThreadPoolTaskExecutor indexingTaskExecutor(MeterRegistry meters) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("document-index-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.setRejectedExecutionHandler((task, threadPool) -> {
      Counter.builder("rag.indexing.executor.rejected").register(meters).increment();
      throw new RejectedExecutionException("Indexing worker queue is full or shutting down");
    });
    Gauge.builder("rag.indexing.executor.active", executor,
        pool -> pool.getThreadPoolExecutor().getActiveCount()).register(meters);
    Gauge.builder("rag.indexing.executor.queued", executor,
        pool -> pool.getThreadPoolExecutor().getQueue().size()).register(meters);
    Gauge.builder("rag.indexing.executor.queue.capacity", executor,
        pool -> pool.getThreadPoolExecutor().getQueue().remainingCapacity()).register(meters);
    return executor;
  }
}
