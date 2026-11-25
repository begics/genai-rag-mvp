package com.begics.rag.model;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnProperty(name = "rag.provider", havingValue = "infomaniak")
public class InfomaniakAiModels {
  @Bean
  EmbeddingModel infomaniakEmbeddingModel(InfomaniakAiProperties properties) {
    org.springframework.ai.embedding.EmbeddingModel model = OpenAiEmbeddingModel.builder()
        .options(embeddingOptions(properties))
        .build();
    return new EmbeddingModel() {
      @Override
      public float[] embed(String text) {
        return validateEmbeddingDimensions(model.embed(text), properties.embeddingDimensions());
      }

      @Override
      public String modelId() {
        return properties.embeddingModel();
      }

      @Override
      public java.util.List<float[]> embedAll(java.util.List<String> texts) {
        return model.embed(texts).stream()
            .map(vector -> validateEmbeddingDimensions(vector, properties.embeddingDimensions()))
            .toList();
      }
    };
  }

  @Bean
  ChatModel infomaniakChatModel(InfomaniakAiProperties properties) {
    org.springframework.ai.chat.model.ChatModel model = OpenAiChatModel.builder()
        .options(chatOptions(properties))
        .build();
    return (question, context) -> model.call(
        "Answer using only the supplied context. Cite its content.\nContext:\n" + context + "\nQuestion:\n" + question);
  }

  @Bean
  Reranker infomaniakReranker(InfomaniakAiProperties properties) {
    return new InfomaniakReranker(RestClient.builder(), properties);
  }

  static OpenAiEmbeddingOptions embeddingOptions(InfomaniakAiProperties properties) {
    return OpenAiEmbeddingOptions.builder()
        .model(properties.embeddingModel())
        .baseUrl(properties.baseUrl())
        .apiKey(properties.apiKey())
        .build();
  }

  static OpenAiChatOptions chatOptions(InfomaniakAiProperties properties) {
    return OpenAiChatOptions.builder()
        .model(properties.chatModel())
        .baseUrl(properties.baseUrl())
        .apiKey(properties.apiKey())
        .build();
  }

  @Bean
  InfomaniakAiProperties infomaniakAiProperties(org.springframework.core.env.Environment environment) {
    String productId = required(environment, "rag.infomaniak.product-id", "INFOMANIAK_AI_PRODUCT_ID");
    String apiKey = required(environment, "rag.infomaniak.api-key", "INFOMANIAK_AI_API_KEY");
    String chatModel = required(environment, "rag.infomaniak.chat-model", "INFOMANIAK_AI_CHAT_MODEL");
    String embeddingModel = required(environment, "rag.infomaniak.embedding-model", "INFOMANIAK_AI_EMBEDDING_MODEL");
    String rerankModel = required(environment, "rag.infomaniak.rerank-model", "INFOMANIAK_AI_RERANK_MODEL");
    int dimensions = EmbeddingDimensions.configured(environment);
    try {
      int parsedProductId = Integer.parseInt(productId);
      if (parsedProductId <= 0) {
        throw new IllegalStateException("INFOMANIAK_AI_PRODUCT_ID must be a positive integer");
      }
      return new InfomaniakAiProperties(
          parsedProductId, apiKey, chatModel, embeddingModel, rerankModel, dimensions);
    } catch (NumberFormatException exception) {
      throw new IllegalStateException(
          "INFOMANIAK_AI_PRODUCT_ID must be an integer", exception);
    }
  }

  private static String required(
      org.springframework.core.env.Environment environment, String property, String environmentVariable) {
    String value = environment.getProperty(property);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(environmentVariable + " must be configured when RAG_PROVIDER=infomaniak");
    }
    return value;
  }

  static float[] validateEmbeddingDimensions(float[] embedding, int expectedDimensions) {
    if (embedding.length != expectedDimensions) {
      throw new IllegalStateException(
          "Infomaniak returned " + embedding.length + " embedding dimensions; RAG_EMBEDDING_DIMENSIONS is "
              + expectedDimensions);
    }
    return embedding;
  }

  record InfomaniakAiProperties(
      int productId, String apiKey, String chatModel, String embeddingModel, String rerankModel,
      int embeddingDimensions) {
    String baseUrl() {
      return "https://api.infomaniak.com/2/ai/" + productId + "/openai/v1";
    }

    String rerankUrl() {
      return "https://api.infomaniak.com/2/ai/" + productId + "/cohere/v2/rerank";
    }
  }
}
