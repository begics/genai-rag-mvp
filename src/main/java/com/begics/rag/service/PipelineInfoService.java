package com.begics.rag.service;

import com.begics.rag.contract.ApiModels.PipelineInfoResponse;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class PipelineInfoService {
  private static final String DEFAULT_AGENT_PLANNER_MODEL =
      "google/gemma-4-31B-it";

  private final Environment environment;

  public PipelineInfoService(Environment environment) {
    this.environment = environment;
  }

  public PipelineInfoResponse current() {
    String provider = environment.getProperty("rag.provider", "offline");
    if ("infomaniak".equalsIgnoreCase(provider)) {
      return new PipelineInfoResponse(
          "Infomaniak AI",
          "OpenAI-compatible Embeddings API",
          environment.getProperty("rag.infomaniak.embedding-model", "Not configured"),
          environment.getProperty("rag.embedding-dimensions", Integer.class, 3584),
          "Cohere-compatible Rerank API",
          environment.getProperty("rag.infomaniak.rerank-model", "Not configured"),
          "OpenAI-compatible Chat Completions API",
          environment.getProperty("rag.infomaniak.chat-model", "Not configured"),
          "Infomaniak structured-output planner · "
              + agentPlannerModel()
              + " · configured, not live health-checked",
          "Workspace hybrid search; indexed text/Markdown/CSV/PDF paragraph comparison",
          3,
          null,
          null);
    }
    if ("ollama".equalsIgnoreCase(provider)) {
      boolean infomaniakReranker = "infomaniak".equalsIgnoreCase(
          environment.getProperty("rag.ollama.rerank-provider", "infomaniak"));
      int contextTokens = environment.getProperty(
          "rag.ollama.context-tokens", Integer.class, 32_768);
      return new PipelineInfoResponse(
          "Local Ollama",
          "Ollama embeddings API",
          environment.getProperty("rag.ollama.embedding-model", "embeddinggemma-2"),
          environment.getProperty("rag.ollama.embedding-dimensions", Integer.class, 768),
          infomaniakReranker ? "Infomaniak Cohere-compatible Rerank API"
              : "Local Ollama cross-encoder reranking API",
          environment.getProperty(
              infomaniakReranker ? "rag.infomaniak.rerank-model" : "rag.ollama.rerank-model",
              infomaniakReranker ? "Not configured" : "AuditAid/Qwen3_Reranker:0.6B_Q8"),
          "Ollama chat API",
          environment.getProperty("rag.ollama.chat-model", "gemma4:12b-mlx"),
          "Local Gemma structured-output planner · "
              + environment.getProperty("rag.ollama.chat-model", "gemma4:12b-mlx"),
          "Workspace hybrid search; indexed text/Markdown/CSV/PDF paragraph comparison",
          3,
          contextTokens,
          contextTokens);
    }
    boolean ollamaPlanner = "ollama".equalsIgnoreCase(
        environment.getProperty("rag.offline-agent-planner.provider", "deterministic"));
    String planner = ollamaPlanner
            ? "Local Ollama planner · "
                + environment.getProperty("rag.offline-agent-planner.ollama.model", "gemma4:31b")
                + " · configured, not live health-checked"
            : "Deterministic local single-query planner · no provider calls";
    return new PipelineInfoResponse(
        "Offline demo mode",
        "Deterministic local embedding implementation",
        "hash-based demo embeddings",
        environment.getProperty("rag.embedding-dimensions", Integer.class, 3584),
        "No external reranker call",
        "pass-through",
        "No external chat LLM call",
        "canned demo response",
        planner,
        "Workspace hybrid search; indexed text/Markdown/CSV/PDF paragraph comparison",
        3,
        null,
        ollamaPlanner ? environment.getProperty(
            "rag.ollama.context-tokens", Integer.class, 32_768) : null);
  }

  private String agentPlannerModel() {
    String configured = environment.getProperty("rag.infomaniak.agent-planner-model");
    return configured == null || configured.isBlank() ? DEFAULT_AGENT_PLANNER_MODEL : configured;
  }
}
