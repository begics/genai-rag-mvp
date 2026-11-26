package com.begics.rag.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModelConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(
          DeterministicModels.class, InfomaniakAiModels.class, InfomaniakSpeechTranscription.class);

  @Test
  void usesOfflineModelsByDefault() {
    contextRunner.withPropertyValues("rag.provider=offline").run(context -> {
      assertThat(context).hasSingleBean(EmbeddingModel.class);
      assertThat(context).hasSingleBean(ChatModel.class);
      assertThat(context).doesNotHaveBean(StructuredChatModel.class);
      assertThat(context).hasSingleBean(Reranker.class);
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
          assertThat(context).hasSingleBean(InfomaniakSpeechTranscription.class);
          InfomaniakAiModels.InfomaniakAiProperties properties =
              context.getBean(InfomaniakAiModels.InfomaniakAiProperties.class);
          var chatOptions = InfomaniakAiModels.chatOptions(properties);
          assertThat(chatOptions.getBaseUrl())
              .isEqualTo("https://api.infomaniak.com/2/ai/12345/openai/v1");
          assertThat(chatOptions.getApiKey()).isEqualTo("test-key");
          assertThat(chatOptions.getModel()).isEqualTo("test-chat");
          var embeddingOptions = InfomaniakAiModels.embeddingOptions(properties);
          assertThat(embeddingOptions.getBaseUrl())
              .isEqualTo("https://api.infomaniak.com/2/ai/12345/openai/v1");
          assertThat(embeddingOptions.getApiKey()).isEqualTo("test-key");
          assertThat(embeddingOptions.getModel()).isEqualTo("test-embedding");
          assertThat(embeddingOptions.getDimensions()).isNull();
          assertThat(properties.rerankModel()).isEqualTo("test-reranker");
          assertThat(properties.rerankUrl())
              .isEqualTo("https://api.infomaniak.com/2/ai/12345/cohere/v2/rerank");
          assertThat(InfomaniakAiModels.validateEmbeddingDimensions(new float[768], 768)).hasSize(768);
        });
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
}
