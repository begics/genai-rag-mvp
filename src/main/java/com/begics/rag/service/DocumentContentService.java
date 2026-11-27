package com.begics.rag.service;

import com.begics.rag.contract.ApiModels;
import com.begics.rag.store.DocumentStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentContentService {
  private final DocumentStore documents;

  public DocumentContentService(DocumentStore documents) {
    this.documents = documents;
  }

  public ApiModels.DocumentContentResponse read(
      UUID workspaceId, UUID documentId, int chunkNumber, String excerpt) {
    if (chunkNumber < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source chunk number must not be negative");
    }
    if (excerpt != null && excerpt.length() > 2_000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source excerpt is too long");
    }
    DocumentStore.SourceChunk sourceChunk = documents.sourceChunk(workspaceId, documentId, chunkNumber)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    WorkspaceAccessService.requireObjectKey(sourceChunk.source(), workspaceId);
    String fullText = sourceChunk.content();
    int fullMatchOffset = excerpt == null || excerpt.isBlank() ? -1 : fullText.indexOf(excerpt);
    int start = fullMatchOffset < 0 ? 0 : Math.max(0, fullMatchOffset - 1_000);
    int end = Math.min(fullText.length(), start + 5_000);
    String text = fullText.substring(start, end);
    int matchOffset = fullMatchOffset < 0 ? -1 : fullMatchOffset - start;
    String filename = sourceChunk.source().substring(sourceChunk.source().lastIndexOf('/') + 1);
    return new ApiModels.DocumentContentResponse(documentId.toString(), filename, text, matchOffset);
  }
}
