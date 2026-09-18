package com.urlcheck.ai.knowledge.retrieval;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.urlcheck.ai.knowledge.KnowledgeProperties;

/**
 * Finds the knowledge passages that are worth showing the model.
 *
 * <p>Plain vector similarity with two guards: a result cap, so one question
 * cannot flood the context, and a similarity floor, so a question the
 * documentation does not answer comes back empty instead of handing the model
 * three loosely related paragraphs to reason from. Passing nothing back is the
 * honest answer, and the assistant can then say it does not know.
 *
 * <p>This only reads. Nothing here writes to the knowledge base or the index;
 * that is {@code KnowledgeIngestionService}'s job, and keeping the two apart is
 * what lets retrieval run on every question without side effects.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeRetrievalService {

    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;

    public KnowledgeRetrievalService(VectorStore vectorStore, KnowledgeProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /**
     * @param question what to look for, in the user's own words
     * @param category optional category to search within, or {@code null}
     * @return the passages above the similarity floor, best first; empty when
     *         nothing is close enough
     * @throws RuntimeException when the index cannot be reached, which the
     *         caller reports as an error rather than as "nothing found"
     */
    public List<FoundChunk> search(String question, String category) {
        SearchRequest.Builder request = SearchRequest.builder()
                .query(question)
                .topK(this.properties.topK())
                .similarityThreshold(this.properties.similarityThreshold());

        if (StringUtils.hasText(category)) {
            request.filterExpression(new FilterExpressionBuilder().eq("category", category).build());
        }

        return this.vectorStore.similaritySearch(request.build()).stream()
                .map(KnowledgeRetrievalService::toChunk)
                .toList();
    }

    private static FoundChunk toChunk(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new FoundChunk(
                value(metadata, "document"),
                value(metadata, "category"),
                value(metadata, "heading"),
                document.getText(),
                document.getScore());
    }

    /**
     * Metadata comes back from Qdrant as whatever was stored, and a document that
     * predates a metadata key simply does not have it, so a missing value is
     * reported as empty rather than as "null".
     */
    private static String value(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null || value instanceof String text && text.isBlank() ? "" : String.valueOf(value);
    }

    /**
     * One retrieved passage.
     *
     * @param document which knowledge document it came from
     * @param category that document's category
     * @param heading  the section inside it, empty when the text precedes any heading
     * @param text     the passage itself
     * @param score    the cosine similarity that got it here
     */
    public record FoundChunk(String document, String category, String heading, String text, Double score) {
    }
}