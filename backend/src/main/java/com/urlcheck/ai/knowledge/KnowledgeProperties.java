package com.urlcheck.ai.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Settings for the knowledge base, bound from {@code app.ai.knowledge.*} and
 * therefore from the environment.
 *
 * <p>Off unless the deployment turns it on, so an installation that has not
 * thought about the knowledge base behaves exactly as it did before: no model
 * is loaded, no collection is touched, and the assistant answers from its tools
 * alone.
 *
 * @param enabled             run knowledge ingestion and retrieval at all
 * @param ingestOnStart       compare the knowledge files with the ledger when the
 *                            application starts; because the comparison is a hash
 *                            check, an unchanged file costs nothing, which is what
 *                            makes frequent restarts free
 * @param collectionName      the Qdrant collection the chunks live in
 * @param location            where the knowledge documents are, as a Spring resource
 *                            pattern; a knob so a test can point at a fixture
 *                            directory instead of the shipped documents
 * @param topK                how many chunks one question may retrieve
 * @param similarityThreshold lowest cosine similarity worth handing to the model;
 *                            below it a chunk is noise
 * @param maxChunkCharacters  how long a chunk may get before it is split
 * @param qdrant              where the vectors are stored
 * @param embedding           the local model that turns text into vectors
 */
@ConfigurationProperties(prefix = "app.ai.knowledge")
public record KnowledgeProperties(
        boolean enabled,
        boolean ingestOnStart,
        String collectionName,
        String location,
        int topK,
        double similarityThreshold,
        int maxChunkCharacters,
        Qdrant qdrant,
        Embedding embedding) {

    /**
     * The Qdrant connection. Both values come from the environment and are never
     * committed; the key is required because Qdrant Cloud rejects anonymous
     * clients.
     */
    public record Qdrant(String url, String apiKey) {
    }

    /**
     * The embedding model that runs inside the backend. Both resources are
     * classpath entries that ship in the jar, so nothing is downloaded at runtime
     * and the deployment needs no model step.
     *
     * @param model             the ONNX file
     * @param tokenizer         the {@code tokenizer.json} of the same model
     * @param dimensions        the model's vector width; it also fixes the Qdrant
     *                          collection size, which cannot be changed later
     *                          without recreating the collection
     * @param maxSequenceLength tokens per text; longer input is truncated
     *                          head-first, which keeps the CLS token
     */
    public record Embedding(Resource model, Resource tokenizer, int dimensions, int maxSequenceLength) {
    }
}