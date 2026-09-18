package com.urlcheck.ai.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

import com.urlcheck.ai.knowledge.KnowledgeProperties;

class KnowledgeRetrievalServiceTest {

    private static final int TOP_K = 3;
    private static final double THRESHOLD = 0.55;

    private final VectorStore vectorStore = mock(VectorStore.class);

    private final KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(vectorStore,
            new KnowledgeProperties(true, true, "test_knowledge", "classpath*:ai/knowledge/**/*.md",
                    TOP_K, THRESHOLD, 800,
                    new KnowledgeProperties.Qdrant("https://example.cloud.qdrant.io", "key"),
                    new KnowledgeProperties.Embedding(new ClassPathResource("model.onnx"),
                            new ClassPathResource("tokenizer.json"), 512, 512)));

    @Test
    void asksForTheConfiguredChunkCountAboveTheConfiguredFloor() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retrieval.search("how is a change detected", null);

        SearchRequest request = capturedRequest();
        assertThat(request.getQuery()).isEqualTo("how is a change detected");
        assertThat(request.getTopK()).isEqualTo(TOP_K);
        assertThat(request.getSimilarityThreshold()).isEqualTo(THRESHOLD);
        assertThat(request.hasFilterExpression()).isFalse();
    }

    @Test
    void narrowsToACategoryWhenOneIsGiven() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        retrieval.search("how is a change detected", "business");

        assertThat(capturedRequest().hasFilterExpression()).isTrue();
    }

    @Test
    void mapsTheStoredMetadataOntoTheChunkItCameFrom() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder()
                .text("URLCheck compares the SHA-256 hash.")
                .metadata(Map.of("document", "business/change-detection.md",
                        "category", "business",
                        "heading", "Process"))
                .score(0.81)
                .build()));

        List<KnowledgeRetrievalService.FoundChunk> found = retrieval.search("change detection", null);

        assertThat(found).singleElement().satisfies(chunk -> {
            assertThat(chunk.document()).isEqualTo("business/change-detection.md");
            assertThat(chunk.category()).isEqualTo("business");
            assertThat(chunk.heading()).isEqualTo("Process");
            assertThat(chunk.text()).contains("SHA-256");
            assertThat(chunk.score()).isEqualTo(0.81);
        });
    }

    @Test
    void reportsMissingMetadataAsEmptyRatherThanAsNull() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(Document.builder().text("body").build()));

        assertThat(retrieval.search("anything", null)).singleElement().satisfies(chunk -> {
            assertThat(chunk.document()).isEmpty();
            assertThat(chunk.category()).isEmpty();
            assertThat(chunk.heading()).isEmpty();
        });
    }

    private SearchRequest capturedRequest() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }
}