package com.urlcheck.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.urlcheck.ai.knowledge.retrieval.KnowledgeRetrievalService;
import com.urlcheck.ai.tool.ToolResult;

class KnowledgeToolsTest {

    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final KnowledgeTools tools = new KnowledgeTools(retrieval);

    @Test
    void handsTheModelThePassagesThatMatched() {
        when(retrieval.search("how are changes detected", null)).thenReturn(List.of(
                new KnowledgeRetrievalService.FoundChunk("business/change-detection.md", "business",
                        "Process", "URLCheck compares hashes.", 0.9)));

        ToolResult result = tools.searchKnowledge("how are changes detected", null);

        assertThat(result.error()).isNull();
        assertThat(result.data()).isInstanceOf(KnowledgeTools.Passages.class);
        KnowledgeTools.Passages passages = (KnowledgeTools.Passages) result.data();
        assertThat(passages.passages()).singleElement().satisfies(passage -> {
            assertThat(passage.document()).isEqualTo("business/change-detection.md");
            assertThat(passage.heading()).isEqualTo("Process");
            assertThat(passage.text()).contains("hashes");
        });
    }

    @Test
    void saysSoWhenTheDocumentationHasNothingCloseEnough() {
        when(retrieval.search(any(), any())).thenReturn(List.of());

        ToolResult result = tools.searchKnowledge("how do I bake bread", null);

        assertThat(result.error()).isNull();
        assertThat(result.data()).isInstanceOf(KnowledgeTools.NoMatch.class);
    }

    @Test
    void reportsAFailureInsteadOfPretendingNothingWasFound() {
        when(retrieval.search(any(), any())).thenThrow(new IllegalStateException("the index is unreachable"));

        ToolResult result = tools.searchKnowledge("how are changes detected", null);

        assertThat(result.data()).isNull();
        assertThat(result.error()).contains("could not be searched");
    }
}