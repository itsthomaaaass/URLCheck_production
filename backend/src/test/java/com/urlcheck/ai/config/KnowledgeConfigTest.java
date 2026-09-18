package com.urlcheck.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.urlcheck.ai.knowledge.KnowledgeTools;
import com.urlcheck.ai.knowledge.embedding.OnnxEmbeddingModel;
import com.urlcheck.ai.knowledge.retrieval.KnowledgeRetrievalService;

/**
 * The switches, not the behaviour: with the knowledge base off nothing appears,
 * with it on but unconfigured the context fails loudly, and with it configured
 * the pieces are assembled. The Qdrant client is stubbed, because the point is
 * wiring rather than reaching a real cluster.
 */
class KnowledgeConfigTest {

    private static final String[] CONFIGURED = {
            "app.ai.knowledge.enabled=true",
            "app.ai.knowledge.ingest-on-start=false",
            "app.ai.knowledge.collection-name=test_knowledge",
            "app.ai.knowledge.location=classpath*:ai/knowledge/**/*.md",
            "app.ai.knowledge.top-k=4",
            "app.ai.knowledge.similarity-threshold=0.5",
            "app.ai.knowledge.max-chunk-characters=800",
            "app.ai.knowledge.embedding.model=classpath:ai/models/bge-small-zh-v1.5/model_quantized.onnx",
            "app.ai.knowledge.embedding.tokenizer=classpath:ai/models/bge-small-zh-v1.5/tokenizer.json",
            "app.ai.knowledge.embedding.dimensions=512",
            "app.ai.knowledge.embedding.max-sequence-length=512",
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeConfig.class, KnowledgeRetrievalService.class);

    @Test
    void staysAbsentWhenTheKnowledgeBaseIsOff() {
        this.runner.withPropertyValues("app.ai.knowledge.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(QdrantClient.class);
            assertThat(context).doesNotHaveBean(VectorStore.class);
            assertThat(context).doesNotHaveBean(KnowledgeTools.class);
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
        });
    }

    @Test
    void failsFastWhenEnabledWithoutAQdrantKey() {
        this.runner.withPropertyValues(CONFIGURED).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root).isInstanceOf(IllegalStateException.class).hasMessageContaining("QDRANT_API_KEY");
        });
    }

    @Test
    void assemblesTheEmbedderTheStoreAndTheToolWhenConfigured() {
        this.runner.withBean(QdrantClient.class, () -> mock(QdrantClient.class))
                .withPropertyValues(CONFIGURED)
                .withPropertyValues("app.ai.knowledge.qdrant.url=https://example.cloud.qdrant.io",
                        "app.ai.knowledge.qdrant.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OnnxEmbeddingModel.class);
                    assertThat(context).hasSingleBean(VectorStore.class);
                    assertThat(context).hasSingleBean(KnowledgeTools.class);
                    assertThat(context).hasSingleBean(KnowledgeRetrievalService.class);
                    assertThat(context.getBean(VectorStore.class)).isInstanceOf(QdrantVectorStore.class);
                });
    }
}