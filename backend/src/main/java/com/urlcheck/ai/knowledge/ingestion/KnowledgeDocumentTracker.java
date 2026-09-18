package com.urlcheck.ai.knowledge.ingestion;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers which version of each knowledge document is already embedded.
 *
 * <p>This is what makes ingestion incremental and restarts free: with the hashes
 * in hand, the ingestor can tell an unchanged document from a changed one
 * without calling the model, and it is deliberately kept in MySQL rather than in
 * Qdrant, because Qdrant is the search index and not a place to keep state
 * about itself.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeDocumentTracker {

    private final KnowledgeDocumentMapper mapper;

    public KnowledgeDocumentTracker(KnowledgeDocumentMapper mapper) {
        this.mapper = mapper;
    }

    /** Every tracked document, mapped from its path to the hash that is embedded. */
    public Map<String, String> hashesByDocument() {
        Map<String, String> hashes = new HashMap<>();
        for (KnowledgeDocumentState state : this.mapper.findAll()) {
            hashes.put(state.getDocument(), state.getContentHash());
        }
        return hashes;
    }

    @Transactional
    public void record(String document, String contentHash, int chunkCount) {
        KnowledgeDocumentState state = new KnowledgeDocumentState();
        state.setDocument(document);
        state.setContentHash(contentHash);
        state.setChunkCount(chunkCount);
        this.mapper.upsert(state);
    }

    @Transactional
    public void forget(String document) {
        this.mapper.deleteByDocument(document);
    }
}