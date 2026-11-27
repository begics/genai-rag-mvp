package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;

class ModelConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(
          DeterministicModels.class, InfomaniakAiModels.class, InfomaniakSpeechTranscription.class,
          InfomaniakGroundingVerifier.class)
      .withBean(tools.jackson.databind.ObjectMapper.class, () -> JsonMapper.builder().build());

  @Test
  void usesOfflineModelsByDefault() {
    contextRunner.withPropertyValues("rag.provider=offline").run(context -> {
      assertThat(context).hasSingleBean(EmbeddingModel.class);
      assertThat(context).hasSingleBean(ChatModel.class);
      assertThat(context).doesNotHaveBean(StructuredChatModel.class);
      assertThat(context).hasSingleBean(Reranker.class);
      assertThat(context).hasSingleBean(AgentPlanner.class);
      assertThat(context).hasSingleBean(GroundingVerifier.class);
    });
  }

  @Test
  void offlineEmbeddingDimensionMatchesTheConfiguredPgvectorDimension() {
    contextRunner.withPropertyValues("rag.provider=offline", "rag.embedding-dimensions=768").run(context ->
        assertThat(context.getBean(EmbeddingModel.class).embed("test")).hasSize(768));
  }

  @Test
  void configuresInfomaniakModelsWithoutMakingProviderCalls() {
    contextRunner.withPropertyValues(
        "rag.provider=infomaniak",
        "rag.infomaniak.product-id=12345",
        "rag.infomaniak.api-key=test-key",
        "rag.infomaniak.chat-model=test-chat",
        "rag.infomaniak.embedding-model=test-embedding",
        "rag.infomaniak.rerank-model=test-reranker",
        "rag.embedding-dimensions=768").run(context -> {
          assertThat(context).hasSingleBean(EmbeddingModel.class);
          assertThat(context).hasSingleBean(ChatModel.class);
          assertThat(context).hasSingleBean(StructuredChatModel.class);
          assertThat(context).hasSingleBean(Reranker.class);
          assertThat(context).hasSingleBean(AgentPlanner.class);
          assertThat(context).hasSingleBean(GroundingVerifier.class);
          assertThat(context).hasSingleBean(InfomaniakSpeechTranscription.class);
          InfomaniakAiModels.InfomaniakAiProperties properties =
              context.getBean(InfomaniakAiModels.InfomaniakAiProperties.class);
          var chatOptions = InfomaniakAiModels.chatOptions(properties);
          assertThat(chatOptions.getBaseUrl())
              .isEqualTo("https://api.infomaniak.com/2/ai/12345/openai/v1");
          assertThat(chatOptions.getApiKey()).isEqualTo("test-key");
          assertThat(chatOptions.getModel()).isEqualTo("test-chat");
          assertThat(chatOptions.getMaxCompletionTokens()).isEqualTo(512);
          assertThat(chatOptions.getTimeout()).isEqualTo(Duration.ofSeconds(60));
          assertThat(chatOptions.getMaxRetries()).isEqualTo(1);
          var embeddingOptions = InfomaniakAiModels.embeddingOptions(properties);
          assertThat(embeddingOptions.getBaseUrl())
              .isEqualTo("https://api.infomaniak.com/2/ai/12345/openai/v1");
          assertThat(embeddingOptions.getApiKey()).isEqualTo("test-key");
          assertThat(embeddingOptions.getModel()).isEqualTo("test-embedding");
          assertThat(embeddingOptions.getDimensions()).isNull();
          assertThat(embeddingOptions.getTimeout()).isEqualTo(Duration.ofSeconds(60));
          assertThat(embeddingOptions.getMaxRetries()).isEqualTo(1);
          assertThat(properties.rerankModel()).isEqualTo("test-reranker");
          assertThat(properties.rerankUrl())
              .isEqualTo("https://api.infomaniak.com/2/ai/12345/cohere/v2/rerank");
          assertThat(properties.agentPlannerModel())
              .isEqualTo("google/gemma-4-31B-it");
          assertThat(InfomaniakAiModels.chatOptions(properties).getModel()).isEqualTo("test-chat");
          assertThat(InfomaniakAiModels.chatOptions(properties, properties.agentPlannerModel()).getModel())
              .isEqualTo("google/gemma-4-31B-it");
          assertThat(InfomaniakAiModels.validateEmbeddingDimensions(new float[768], 768)).hasSize(768);
        });
  }

  @Test
  void configuresProviderTimeoutsRetriesAndConcurrencyLimit() {
    contextRunner.withPropertyValues(
        "rag.provider=infomaniak",
        "rag.infomaniak.product-id=12345",
        "rag.infomaniak.api-key=test-key",
        "rag.infomaniak.chat-model=test-chat",
        "rag.infomaniak.embedding-model=test-embedding",
        "rag.infomaniak.rerank-model=test-reranker",
        "rag.infomaniak.request-timeout-ms=25000",
        "rag.infomaniak.connect-timeout-ms=3000",
        "rag.infomaniak.read-timeout-ms=20000",
        "rag.infomaniak.max-retries=0",
        "rag.provider-limits.max-concurrent-calls=2").run(context -> {
          assertThat(context).hasNotFailed();
          var properties = context.getBean(InfomaniakAiModels.InfomaniakAiProperties.class);
          assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(25));
          assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
          assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(20));
          assertThat(properties.maxRetries()).isZero();
          assertThat(InfomaniakAiModels.chatOptions(properties).getTimeout()).isEqualTo(Duration.ofSeconds(25));
          assertThat(context.getBean(ProviderCallLimiter.class).maxConcurrentCalls()).isEqualTo(2);
      });
  }

  @Test
  void allowsThePlannerModelToBeOverriddenIndependently() {
    contextRunner.withPropertyValues(
        "rag.provider=infomaniak",
        "rag.infomaniak.product-id=12345",
        "rag.infomaniak.api-key=test-key",
        "rag.infomaniak.chat-model=test-answer-model",
        "rag.infomaniak.agent-planner-model=test-small-planner",
        "rag.infomaniak.embedding-model=test-embedding",
        "rag.infomaniak.rerank-model=test-reranker",
        "rag.embedding-dimensions=768").run(context -> {
          assertThat(context).hasNotFailed();
          InfomaniakAiModels.InfomaniakAiProperties properties =
              context.getBean(InfomaniakAiModels.InfomaniakAiProperties.class);
          assertThat(properties.chatModel()).isEqualTo("test-answer-model");
          assertThat(properties.agentPlannerModel()).isEqualTo("test-small-planner");
          assertThat(InfomaniakAiModels.chatOptions(properties).getModel()).isEqualTo("test-answer-model");
          assertThat(InfomaniakAiModels.chatOptions(properties, properties.agentPlannerModel()).getModel())
              .isEqualTo("test-small-planner");
        });
  }

  @Test
  void keepsTheConfiguredModelWhenUsingStructuredOutputOptions() {
    var properties = new InfomaniakAiModels.InfomaniakAiProperties(
        12345, "test-key", "test-chat", "test-planner", "embedding", "reranker", 768, 256);
    var options = InfomaniakAiModels.structuredOutputOptions(
        properties, "test-small-planner", "{\"type\":\"object\"}");

    assertThat(options.getModel()).isEqualTo("test-small-planner");
    assertThat(options.getMaxCompletionTokens()).isEqualTo(256);
    assertThat(options.getOutputSchema()).isEqualTo("{\"type\":\"object\"}");
  }

  @Test
  void rejectsAnEmbeddingDimensionThatDoesNotMatchTheConfiguredVectorSchema() {
    assertThatIllegalStateException()
        .isThrownBy(() -> InfomaniakAiModels.validateEmbeddingDimensions(new float[1024], 768))
        .withMessageContaining("RAG_EMBEDDING_DIMENSIONS is 768");
  }

  @Test
  void requiresInfomaniakConfigurationWhenItsProviderIsSelected() {
    contextRunner.withPropertyValues("rag.provider=infomaniak").run(context ->
        assertThat(context).hasFailed().getFailure()
            .hasMessageContaining("INFOMANIAK_AI_PRODUCT_ID must be configured"));
  }

  @Test
  void rejectsEmbeddingDimensionsBeyondThePgvectorHalfvecHnswLimit() {
    contextRunner.withPropertyValues(
        "rag.provider=infomaniak",
        "rag.infomaniak.product-id=12345",
        "rag.infomaniak.api-key=test-key",
        "rag.infomaniak.chat-model=test-chat",
        "rag.infomaniak.embedding-model=test-embedding",
        "rag.infomaniak.rerank-model=test-reranker",
        "rag.embedding-dimensions=4001").run(context ->
        assertThat(context).hasFailed().getFailure()
            .hasMessageContaining("RAG_EMBEDDING_DIMENSIONS must be between 1 and 4000"));
  }

  @Test
  void rejectsAnInvalidCompletionTokenCeiling() {
    contextRunner.withPropertyValues(
        "rag.provider=infomaniak",
        "rag.infomaniak.product-id=12345",
        "rag.infomaniak.api-key=test-key",
        "rag.infomaniak.chat-model=test-chat",
        "rag.infomaniak.embedding-model=test-embedding",
        "rag.infomaniak.rerank-model=test-reranker",
        "rag.embedding-dimensions=768",
        "rag.guardrails.max-completion-tokens=0").run(context ->
        assertThat(context).hasFailed().getFailure()
            .hasMessageContaining("RAG_GUARDRAILS_MAX_COMPLETION_TOKENS"));
  }
}
