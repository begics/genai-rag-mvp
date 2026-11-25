package com.begics.rag.service;

import com.begics.rag.contract.ApiModels.IndexedDocumentResponse;
import com.begics.rag.store.DocumentStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IndexedDocumentService {
  private final DocumentStore documents;

  public IndexedDocumentService(DocumentStore documents) {
    this.documents = documents;
  }

  public List<IndexedDocumentResponse> list(UUID workspaceId) {
    return documents.listDocuments(workspaceId).stream()
        .map(document -> new IndexedDocumentResponse(document.id(), document.source(), document.chunks()))
        .toList();
  }
}
