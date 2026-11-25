package com.begics.rag.api;

import com.begics.rag.contract.ApiModels.PipelineInfoResponse;
import com.begics.rag.service.PipelineInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PipelineInfoController {
  private final PipelineInfoService pipelineInfo;

  public PipelineInfoController(PipelineInfoService pipelineInfo) {
    this.pipelineInfo = pipelineInfo;
  }

  @GetMapping("/pipeline-info")
  public PipelineInfoResponse current() {
    return pipelineInfo.current();
  }
}
