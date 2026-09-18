package com.urlcheck.ai.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.urlcheck.ai.knowledge.KnowledgeProperties;
import com.urlcheck.ai.knowledge.KnowledgeTools;
import com.urlcheck.ai.knowledge.QdrantEndpoint;
import com.urlcheck.ai.knowledge.embedding.OnnxEmbeddingModel;
import com.urlcheck.ai.knowledge.retrieval.KnowledgeRetrievalService;

/**
 * Wires the knowledge base: the embedding model, the Qdrant connection and the
 * vector store built on both.
 *
 * <p>Built by hand like {@link AiConfig}, so the feature is present only when it
 * is explicitly enabled and configured. The embedding model is local, so the
 * only credential here is Qdrant's; it is read from the environment and never
 * from a compiled-in default.
 *
 * <p>The rest of the feature (ingestion, retrieval, the tool) are ordinary
 * components carrying the same switch, which is why this class only holds the
 * three pieces that need a constructor argument nobody else can supply.
 */
@Configuration
@EnableConfigurationProperties(KnowledgeProperties.class)
@ConditionalOnProperty(prefix = "app.ai.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeConfig {

    /**
     * The embedder, loaded from the classpath. It is a bean with a destroy
     * method because it owns a native ONNX session, which has to be released on
     * shutdown.
     *
     * @throws IllegalStateException from the model's own initialisation when the
     *         files are missing or do not match the configured dimensions, so a
     *         broken setup fails at startup instead of on the first question
     */
    @Bean(destroyMethod = "close")
    OnnxEmbeddingModel knowledgeEmbeddingModel(KnowledgeProperties properties) {
        KnowledgeProperties.Embedding embedding = properties.embedding();
        return new OnnxEmbeddingModel(embedding.model(), embedding.tokenizer(),
                embedding.dimensions(), embedding.maxSequenceLength());
    }

    /**
     * The Qdrant connection itself. The SDK client is a bean rather than a local
     * so its gRPC channel is closed on shutdown.
     *
     * <p>Backs off when the context already has one, which is how a test supplies
     * a stub without touching a real cluster.
     *
     * @throws IllegalStateException when the URL or the key is missing, so a
     *         deployment that enabled the knowledge base without configuring it
     *         fails loudly at startup
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(QdrantClient.class)
    QdrantClient knowledgeQdrantClient(KnowledgeProperties properties) {
        // The whole qdrant block is absent when nothing under
        // app.ai.knowledge.qdrant.* is set, and a record binding gives null
        // rather than an empty record. Both halves are checked by hand so the
        // failure names the environment variable instead of a null dereference.
        KnowledgeProperties.Qdrant qdrant = properties.qdrant();
        String apiKey = qdrant == null ? null : qdrant.apiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "app.ai.knowledge.enabled=true but app.ai.knowledge.qdrant.api-key is empty:"
                            + " set QDRANT_API_KEY, or turn the knowledge base off with"
                            + " AI_KNOWLEDGE_ENABLED=false");
        }
        if (!StringUtils.hasText(qdrant.url())) {
            throw new IllegalStateException(
                    "app.ai.knowledge.enabled=true but app.ai.knowledge.qdrant.url is empty:"
                            + " set QDRANT_URL, or turn the knowledge base off with"
                            + " AI_KNOWLEDGE_ENABLED=false");
        }

        QdrantEndpoint endpoint = QdrantEndpoint.parse(qdrant.url());
        return new QdrantClient(QdrantGrpcClient
                .newBuilder(endpoint.host(), endpoint.port(), endpoint.useTls())
                .withApiKey(apiKey)
                .build());
    }

    /**
     * The vector store the ingestor writes to and retrieval reads from.
     *
     * <p>Schema initialisation is left off on purpose. Left on, the store would
     * create the collection while the context is starting and a Qdrant that is
     * briefly unreachable would abort the whole application, URL monitoring
     * included. The collection is created by
     * {@link com.urlcheck.ai.knowledge.ingestion.KnowledgeIngestionService}
     * instead, where a failure is logged and retried rather than fatal.
     */
    @Bean
    VectorStore knowledgeVectorStore(QdrantClient knowledgeQdrantClient,
            OnnxEmbeddingModel knowledgeEmbeddingModel, KnowledgeProperties properties) {
        return QdrantVectorStore.builder(knowledgeQdrantClient, knowledgeEmbeddingModel)
                .collectionName(properties.collectionName())
                .initializeSchema(false)
                .build();
    }

    /**
     * The one tool the assistant gains from all of this. It is not a component so
     * that it exists only when the knowledge base does, which is what lets
     * {@link com.urlcheck.ai.agent.AiAgent} take it as an optional dependency.
     */
    @Bean
    KnowledgeTools knowledgeTools(KnowledgeRetrievalService retrieval) {
        return new KnowledgeTools(retrieval);
    }
}