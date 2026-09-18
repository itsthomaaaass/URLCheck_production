package com.urlcheck.ai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Points.UpdateResult;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.urlcheck.ai.knowledge.KnowledgeProperties;

/**
 * The behaviour that makes the knowledge base cheap to run: unchanged documents
 * are never sent to the model, and one document that fails does not drag the
 * others back through ingestion.
 */
class KnowledgeIngestionServiceTest {

    private static final String PATTERN = "classpath*:ai/knowledge-fixture/**/*.md";
    private static final String CHANGED = "business/change-detection.md";
    private static final String UNCHANGED = "architecture/system-architecture.md";

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final KnowledgeDocumentTracker tracker = mock(KnowledgeDocumentTracker.class);
    private final QdrantClient qdrantClient = mock(QdrantClient.class);

    private final KnowledgeProperties properties = new KnowledgeProperties(
            true, true, "test_knowledge", PATTERN, 4, 0.5, 800,
            new KnowledgeProperties.Qdrant("https://example.cloud.qdrant.io", "key"),
            new KnowledgeProperties.Embedding(new ClassPathResource("model.onnx"),
                    new ClassPathResource("tokenizer.json"), 512, 512));

    private final KnowledgeIngestionService ingestion = new KnowledgeIngestionService(
            vectorStore, tracker, qdrantClient, properties);

    @BeforeEach
    void theCollectionAlreadyExists() {
        when(qdrantClient.collectionExistsAsync("test_knowledge")).thenReturn(Futures.immediateFuture(true));
        when(qdrantClient.createPayloadIndexAsync(anyString(), anyString(), any(PayloadSchemaType.class), any(),
                any(), any(), any()))
                .thenReturn(Futures.immediateFuture(UpdateResult.getDefaultInstance()));
    }

    @Test
    void embedsNothingWhenEveryDocumentStillMatchesTheLedger() {
        when(tracker.hashesByDocument()).thenReturn(hashesOnDisk());

        KnowledgeIngestionService.IngestionReport report = ingestion.ingest();

        assertThat(report.unchanged()).isEqualTo(2);
        assertThat(report.embedded()).isZero();
        assertThat(report.changed()).isFalse();
        verify(vectorStore, never()).add(anyList());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(tracker, never()).record(anyString(), anyString(), anyInt());
    }

    @Test
    void reEmbedsOnlyTheDocumentWhoseHashMoved() {
        Map<String, String> ledger = hashesOnDisk();
        ledger.put(CHANGED, "0000000000000000000000000000000000000000000000000000000000000000");
        when(tracker.hashesByDocument()).thenReturn(ledger);

        KnowledgeIngestionService.IngestionReport report = ingestion.ingest();

        assertThat(report.unchanged()).isEqualTo(1);
        assertThat(report.embedded()).isEqualTo(1);
        assertThat(report.failed()).isEmpty();

        List<Document> added = capturedAddedDocuments();
        assertThat(added).isNotEmpty();
        assertThat(added).allSatisfy(document ->
                assertThat(document.getMetadata()).containsEntry("document", CHANGED));
        // Old chunks go first, so two versions of one document never coexist.
        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(tracker).record(eq(CHANGED), eq(KnowledgeDocumentLoader.sha256(
                contentOf(CHANGED).getBytes(java.nio.charset.StandardCharsets.UTF_8))), eq(added.size()));
        verify(tracker, never()).record(eq(UNCHANGED), anyString(), anyInt());
    }

    @Test
    void dropsDocumentsThatAreNoLongerOnDisk() {
        Map<String, String> ledger = hashesOnDisk();
        ledger.put("api/deleted-document.md", "1111111111111111111111111111111111111111111111111111111111111111");
        when(tracker.hashesByDocument()).thenReturn(ledger);

        KnowledgeIngestionService.IngestionReport report = ingestion.ingest();

        assertThat(report.removed()).isEqualTo(1);
        assertThat(report.changed()).isTrue();
        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(tracker).forget("api/deleted-document.md");
    }

    @Test
    void leavesAFailedDocumentPendingWithoutRedoingTheOthers() {
        Map<String, String> ledger = new HashMap<>();
        ledger.put(CHANGED, "0000000000000000000000000000000000000000000000000000000000000000");
        ledger.put(UNCHANGED, "0000000000000000000000000000000000000000000000000000000000000000");
        when(tracker.hashesByDocument()).thenReturn(ledger);
        // The first document, in path order, is the one that fails.
        doThrow(new RuntimeException("the index is unavailable"))
                .doNothing()
                .when(vectorStore).add(anyList());

        KnowledgeIngestionService.IngestionReport report = ingestion.ingest();

        assertThat(report.failed()).containsExactly(UNCHANGED);
        assertThat(report.embedded()).isEqualTo(1);
        verify(tracker, never()).record(eq(UNCHANGED), anyString(), anyInt());
        verify(tracker).record(eq(CHANGED), anyString(), anyInt());
    }

    @Test
    void failsLoudlyWhenQdrantCannotBeReached() {
        when(qdrantClient.collectionExistsAsync("test_knowledge"))
                .thenReturn(Futures.<Boolean>immediateFailedFuture(new RuntimeException("no route to host")));

        assertThatThrownBy(ingestion::ingest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test_knowledge");
        verifyNoInteractions(vectorStore);
    }

    @Test
    void createsTheCollectionWhenItIsMissing() {
        when(qdrantClient.collectionExistsAsync("test_knowledge")).thenReturn(Futures.immediateFuture(false));
        when(qdrantClient.createCollectionAsync(eq("test_knowledge"), any(VectorParams.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(tracker.hashesByDocument()).thenReturn(hashesOnDisk());

        ingestion.ingest();

        verify(qdrantClient).createCollectionAsync(eq("test_knowledge"), any(VectorParams.class));
    }

    /** The hashes the service will compute itself, taken from the same fixtures. */
    private static Map<String, String> hashesOnDisk() {
        Map<String, String> hashes = new HashMap<>();
        for (KnowledgeSource source : new KnowledgeDocumentLoader(
                new PathMatchingResourcePatternResolver(), PATTERN).load()) {
            hashes.put(source.path(), source.hash());
        }
        return hashes;
    }

    private static String contentOf(String path) {
        return new KnowledgeDocumentLoader(new PathMatchingResourcePatternResolver(), PATTERN).load().stream()
                .filter(source -> source.path().equals(path))
                .findFirst()
                .orElseThrow()
                .content();
    }

    @SuppressWarnings("unchecked")
    private List<Document> capturedAddedDocuments() {
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        return new ArrayList<>(captor.getValue());
    }
}