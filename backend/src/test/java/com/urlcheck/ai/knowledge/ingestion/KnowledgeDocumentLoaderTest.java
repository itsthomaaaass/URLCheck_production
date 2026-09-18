package com.urlcheck.ai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Runs against a fixture directory on the test classpath rather than the shipped
 * documents, so it keeps working when the real knowledge base is filled in.
 */
class KnowledgeDocumentLoaderTest {

    private static final String FIXTURE_PATTERN = "classpath*:ai/knowledge-fixture/**/*.md";

    private final KnowledgeDocumentLoader loader = new KnowledgeDocumentLoader(
            new PathMatchingResourcePatternResolver(), FIXTURE_PATTERN);

    @Test
    void loadsEveryDocumentWithItsCategoryAndAPathRelativeToTheRoot() {
        List<KnowledgeSource> sources = loader.load();

        assertThat(sources).extracting(KnowledgeSource::path)
                .containsExactly("architecture/system-architecture.md", "business/change-detection.md");
        assertThat(sources).extracting(KnowledgeSource::category)
                .containsExactly("architecture", "business");
        assertThat(sources).extracting(KnowledgeSource::fileName)
                .containsExactly("system-architecture.md", "change-detection.md");
        assertThat(sources).allSatisfy(source -> assertThat(source.content()).contains("#"));
    }

    @Test
    void hashesTheDocumentBytes() {
        KnowledgeSource source = loader.load().stream()
                .filter(candidate -> candidate.path().endsWith("change-detection.md"))
                .findFirst()
                .orElseThrow();

        assertThat(source.hash()).hasSize(64).containsPattern("[0-9a-f]{64}");
        assertThat(source.hash())
                .isEqualTo(KnowledgeDocumentLoader.sha256(source.content().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void oneChangedByteChangesTheHash() {
        byte[] original = "the body is hashed".getBytes(StandardCharsets.UTF_8);
        byte[] edited = "the body is Hashed".getBytes(StandardCharsets.UTF_8);

        assertThat(KnowledgeDocumentLoader.sha256(original))
                .isEqualTo(KnowledgeDocumentLoader.sha256(original))
                .isNotEqualTo(KnowledgeDocumentLoader.sha256(edited));
    }
}