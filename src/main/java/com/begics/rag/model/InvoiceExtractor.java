package com.begics.rag.model;

import com.begics.rag.domain.InvoiceExtraction;

public interface InvoiceExtractor {
  InvoiceExtraction extract(String text);
}
