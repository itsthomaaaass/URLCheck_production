package com.urlcheck.ai.knowledge.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.urlcheck.ai.knowledge.KnowledgeProperties;

/**
 * Brings the vector store in line with the knowledge files.
 *
 * <p>The files are the source of truth and Qdrant is only a searchable copy of
 * them, so ingestion is a comparison rather than a rebuild: each document is
 * hashed, the ledger says which hash is already embedded, and only the documents
 * whose hash moved are re-embedded. That is what makes a restart cost nothing
 * and a deploy cost only the files that were actually edited.
 *
 * <p>Nothing is embedded through a provider, so an unchanged run makes no
 * network call at all beyond talking to Qdrant.
 *
 * <p>Failures are per document: one file that cannot be embedded stays pending in
 * the ledger while the others are recorded, so the next run retries exactly that
 * file instead of starting over.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final VectorStore vectorStore;
    private final KnowledgeDocumentTracker tracker;
    private final QdrantClient qdrantClient;
    private final KnowledgeProperties properties;
    private final KnowledgeDocumentLoader loader;
    private final KnowledgeChunker chunker;

    public KnowledgeIngestionService(VectorStore vectorStore, KnowledgeDocumentTracker tracker,
            QdrantClient qdrantClient, KnowledgeProperties properties) {
        this.vectorStore = vectorStore;
        this.tracker = tracker;
        this.qdrantClient = qdrantClient;
        this.properties = properties;
        this.loader = new KnowledgeDocumentLoader(
                new PathMatchingResourcePatternResolver(), properties.location());
        this.chunker = new KnowledgeChunker(properties.maxChunkCharacters());
    }

    /**
     * Re-embeds whatever changed and drops whatever is gone.
     *
     * @throws IllegalStateException when Qdrant cannot be reached, because the
     *         caller should say so once rather than let every document fail
     */
    public IngestionReport ingest() {
        ensureCollection();

        List<KnowledgeSource> sources = this.loader.load();
        Map<String, String> alreadyEmbedded = this.tracker.hashesByDocument();

        int unchanged = 0;
        int embedded = 0;
        List<String> failed = new ArrayList<>();

        for (KnowledgeSource source : sources) {
            if (source.hash().equals(alreadyEmbedded.remove(source.path()))) {
                unchanged++;
                continue;
            }

            try {
                int chunks = embed(source);
                this.tracker.record(source.path(), source.hash(), chunks);
                embedded++;
                log.info("Knowledge document {} is now {} chunk(s)", source.path(), chunks);
            }
            catch (RuntimeException ex) {
                // The ledger keeps the previous hash on purpose: the document
                // stays pending and the next run retries this one file only.
                failed.add(source.path());
                log.warn("Knowledge document {} could not be embedded and stays pending", source.path(), ex);
            }
        }

        // What is left in the ledger is tracked but no longer on disk.
        int removed = 0;
        for (String document : alreadyEmbedded.keySet()) {
            try {
                removeChunks(document);
                this.tracker.forget(document);
                removed++;
                log.info("Knowledge document {} is gone; its chunks were dropped", document);
            }
            catch (RuntimeException ex) {
                failed.add(document);
                log.warn("Removed knowledge document {} could not be dropped from the index", document, ex);
            }
        }

        return new IngestionReport(unchanged, embedded, removed, List.copyOf(failed));
    }

    private int embed(KnowledgeSource source) {
        List<KnowledgeChunker.Chunk> chunks = this.chunker.chunk(source.content());

        List<Document> documents = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunker.Chunk chunk = chunks.get(index);
            documents.add(Document.builder()
                    .id(chunkId(source, index))
                    .text(chunk.text())
                    .metadata("document", source.path())
                    .metadata("category", source.category())
                    .metadata("source", source.fileName())
                    .metadata("documentHash", source.hash())
                    .metadata("heading", chunk.heading())
                    .build());
        }

        // Replace, never merge: the old chunks of a changed document must not
        // survive next to the new ones, because retrieval could then hand the
        // model two versions of the same explanation.
        removeChunks(source.path());
        if (!documents.isEmpty()) {
            this.vectorStore.add(documents);
        }
        return documents.size();
    }

    private void removeChunks(String document) {
        this.vectorStore.delete(new FilterExpressionBuilder().eq("document", document).build());
    }

    /**
     * A UUID derived from the document, the chunk's position and the document's
     * hash. Qdrant stores point ids as UUIDs, and deriving them makes an
     * unchanged document re-ingest to the same ids instead of accumulating
     * duplicates.
     */
    private static String chunkId(KnowledgeSource source, int index) {
        String seed = source.path() + "#" + index + "@" + source.hash();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Creates the collection when it is missing.
     *
     * <p>The collection size is the embedding model's width and cannot be changed
     * later, so it is passed from configuration rather than probed. The vector
     * store is deliberately built with schema initialisation off: it creates the
     * collection during bean construction, and a Qdrant that is briefly
     * unreachable would then stop the whole application, including the URL
     * monitoring this assistant only assists.
     */
    private void ensureCollection() {
        String collection = this.properties.collectionName();
        try {
            if (!this.qdrantClient.collectionExistsAsync(collection).get()) {
                int dimensions = this.properties.embedding().dimensions();
                this.qdrantClient.createCollectionAsync(collection, VectorParams.newBuilder()
                        .setSize(dimensions)
                        .setDistance(Distance.Cosine)
                        .build()).get();
                log.info("Created Qdrant collection {} for {} dimensions", collection, dimensions);
            }

            // Dropping a document's old chunks is a filter, and Qdrant rejects a
            // filter over an unindexed payload field with INVALID_ARGUMENT, so
            // without these indexes every changed document would fail to
            // re-embed. Re-creating an index is a no-op, which also repairs a
            // collection that was created before the indexes were part of the
            // setup.
            ensurePayloadIndex(collection, "document");
            ensurePayloadIndex(collection, "category");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking Qdrant collection " + collection, ex);
        }
        catch (ExecutionException ex) {
            throw new IllegalStateException("Qdrant collection " + collection + " could not be read or created", ex);
        }
    }

    /** A keyword index on a metadata field, so it can be filtered and deleted on. */
    private void ensurePayloadIndex(String collection, String field) throws ExecutionException, InterruptedException {
        this.qdrantClient
                .createPayloadIndexAsync(collection, field, PayloadSchemaType.Keyword, null, true, null, null)
                .get();
    }
    /**
     * What one ingestion run did.
     *
     * @param unchanged documents whose hash still matches the ledger
     * @param embedded  documents that were chunked and embedded
     * @param removed   documents that disappeared and were dropped from the index
     * @param failed    documents that were left pending, to be retried next run
     */
    public record IngestionReport(int unchanged, int embedded, int removed, List<String> failed) {

        public boolean changed() {
            return this.embedded > 0 || this.removed > 0;
        }

        public String summary() {
            return "unchanged=" + this.unchanged + ", embedded=" + this.embedded
                    + ", removed=" + this.removed + ", failed=" + this.failed.size();
        }
    }
}