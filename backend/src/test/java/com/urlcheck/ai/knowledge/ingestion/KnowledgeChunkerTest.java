package com.urlcheck.ai.knowledge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class KnowledgeChunkerTest {

    private static final int BUDGET = 200;

    @Test
    void splitsOnHeadingsAndKeepsTheHeadingPathInEveryChunk() {
        KnowledgeChunker chunker = new KnowledgeChunker(800);

        List<KnowledgeChunker.Chunk> chunks = chunker.chunk("""
                # Change Detection

                ## Purpose

                The application decides whether a page changed.

                ## Process

                The body is hashed.

                ### Details

                SHA-256 is compared with the stored hash.
                """);

        assertThat(chunks).extracting(KnowledgeChunker.Chunk::heading)
                .containsExactly("Purpose", "Process", "Process > Details");
        // The prefix is what makes a fragment retrievable on its own.
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text()).startsWith("Change Detection > " + chunk.heading()));
        assertThat(chunks.get(1).text()).contains("The body is hashed.");
    }

    @Test
    void keepsTextThatAppearsBeforeTheFirstHeading() {
        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker(800)
                .chunk("An introduction.\n\n## Section\n\nA body.");

        assertThat(chunks).extracting(KnowledgeChunker.Chunk::heading).containsExactly("", "Section");
        assertThat(chunks.get(0).text()).isEqualTo("An introduction.");
    }

    @Test
    void splitsAnOversizedSectionWithoutLosingText() {
        String paragraph = "word ".repeat(400);

        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker(BUDGET)
                .chunk("# Title\n\n## Section\n\n" + paragraph);

        assertThat(chunks).hasSizeGreaterThan(1);
        // Nothing is dropped and nothing is duplicated: every chunk is within
        // the budget once its short heading prefix is discounted.
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text().length()).isLessThanOrEqualTo(BUDGET + "Title > Section".length() + 2));
        int words = chunks.stream().mapToInt(chunk -> chunk.text().split("word", -1).length - 1).sum();
        assertThat(words).isEqualTo(400);
    }

    @Test
    void treatsAHashInsideAFencedBlockAsText() {
        List<KnowledgeChunker.Chunk> chunks = new KnowledgeChunker(800).chunk("""
                # Title

                ## Section

                ```
                # not a heading
                ###### also not a heading
                ```

                Real body.
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("# not a heading").contains("Real body.");
    }

    @Test
    void returnsNothingForADocumentWithNoText() {
        assertThat(new KnowledgeChunker(800).chunk("")).isEmpty();
        assertThat(new KnowledgeChunker(800).chunk("\n\n   \n")).isEmpty();
        assertThat(new KnowledgeChunker(800).chunk("# Only A Title\n")).isEmpty();
    }
}