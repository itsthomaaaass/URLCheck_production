package com.urlcheck.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QdrantEndpointTest {

    @Test
    void readsAQdrantCloudUrlAsATlsEndpointOnTheGrpcPort() {
        QdrantEndpoint endpoint = QdrantEndpoint.parse(
                "https://12345678-1234-4321-8765-123456789abc.sa-east-1-0.aws.cloud.qdrant.io");

        assertThat(endpoint.host())
                .isEqualTo("12345678-1234-4321-8765-123456789abc.sa-east-1-0.aws.cloud.qdrant.io");
        assertThat(endpoint.port()).isEqualTo(6334);
        assertThat(endpoint.useTls()).isTrue();
    }

    @Test
    void readsAPlainHttpUrlAsUnencrypted() {
        QdrantEndpoint endpoint = QdrantEndpoint.parse("http://localhost:6333");

        assertThat(endpoint.host()).isEqualTo("localhost");
        assertThat(endpoint.port()).isEqualTo(6333);
        assertThat(endpoint.useTls()).isFalse();
    }

    @Test
    void acceptsABareHost() {
        QdrantEndpoint endpoint = QdrantEndpoint.parse("qdrant.internal");

        assertThat(endpoint.host()).isEqualTo("qdrant.internal");
        assertThat(endpoint.port()).isEqualTo(6334);
        assertThat(endpoint.useTls()).isTrue();
    }

    @Test
    void ignoresAnyPathAfterTheHost() {
        assertThat(QdrantEndpoint.parse("https://qdrant.example.com:6334/collections").host())
                .isEqualTo("qdrant.example.com");
    }

    @Test
    void refusesAUrlThatCannotWork() {
        assertThatThrownBy(() -> QdrantEndpoint.parse(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QDRANT_URL");
        assertThatThrownBy(() -> QdrantEndpoint.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QdrantEndpoint.parse("https://"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no host");
        assertThatThrownBy(() -> QdrantEndpoint.parse("https://host:not-a-port"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number");
    }
}