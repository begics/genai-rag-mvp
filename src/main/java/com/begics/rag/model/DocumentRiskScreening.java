package com.begics.rag.model;

import java.util.List;

public interface DocumentRiskScreening {
  ScreeningResult evaluate(String text);

  record ScreeningResult(List<Finding> findings) {}

  record Finding(String category, String evidence, String location, String uncertainty) {}
}
