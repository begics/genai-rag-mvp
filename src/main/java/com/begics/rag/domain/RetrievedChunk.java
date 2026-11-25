package com.begics.rag.domain;

public record RetrievedChunk(String documentId, String source, int chunk, String content, double score) {}
