package com.urlcheck.ai.knowledge.ingestion;

import java.time.LocalDateTime;

/**
 * One row of the ingestion ledger: a knowledge document and the version of it
 * that is already in the vector store.
 *
 * <p>Mutable with getters and setters because MyBatis fills it in, like the
 * other entities in this application.
 */
public class KnowledgeDocumentState {

    private Long id;
    private String document;
    private String contentHash;
    private int chunkCount;
    private LocalDateTime embeddedAt;

    public Long getId() {
        return this.id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDocument() {
        return this.document;
    }

    public void setDocument(String document) {
        this.document = document;
    }

    public String getContentHash() {
        return this.contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public int getChunkCount() {
        return this.chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public LocalDateTime getEmbeddedAt() {
        return this.embeddedAt;
    }

    public void setEmbeddedAt(LocalDateTime embeddedAt) {
        this.embeddedAt = embeddedAt;
    }
}