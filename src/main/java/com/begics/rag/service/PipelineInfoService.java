package com.begics.rag.service;

import com.begics.rag.contract.ApiModels.PipelineInfoResponse;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class PipelineInfoService {
  private static final String DEFAULT_AGENT_PLANNER_MODEL =
      "mistralai/Ministral-3-14B-Instruct-2512";

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
          3);
    }
    return new PipelineInfoResponse(
        "Offline demo mode",
        "Deterministic local embedding implementation",
        "hash-based demo embeddings",
        environment.getProperty("rag.embedding-dimensions", Integer.class, 3584),
        "No external reranker call",
        "pass-through",
        "No external chat LLM call",
        "canned demo response",
        "Deterministic local single-query planner · no provider calls",
        "Workspace hybrid search; indexed text/Markdown/CSV/PDF paragraph comparison",
        3);
  }

  private String agentPlannerModel() {
    String configured = environment.getProperty("rag.infomaniak.agent-planner-model");
    return configured == null || configured.isBlank() ? DEFAULT_AGENT_PLANNER_MODEL : configured;
  }
}
