package com.urlcheck.ai.knowledge;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.urlcheck.ai.knowledge.retrieval.KnowledgeRetrievalService;
import com.urlcheck.ai.tool.ToolResult;

/**
 * The knowledge base as the model sees it: one tool it can decide to call.
 *
 * <p>The assistant's other tools answer questions about the user's own data, and
 * this one answers questions about the application. It is a tool rather than a
 * retrieval step that runs on every message, because most messages are about the
 * user's URLs: searching the documentation first would embed a question that
 * does not need it and hand the model passages that do not help. Leaving the
 * choice to the model keeps the cost and the context on the questions that
 * actually ask about the system.
 *
 * <p>Unlike the URL tools this one is not bound to a user, because the
 * documentation is the same for everybody.
 */
public class KnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTools.class);

    private final KnowledgeRetrievalService retrieval;

    public KnowledgeTools(KnowledgeRetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @Tool(name = "searchKnowledge",
            description = "Search URLCheck's own documentation: how the application works, why it behaves the"
                    + " way it does, and what its rules are. It covers the system architecture, how content"
                    + " changes are detected, the scheduled checker and the timeline, authentication, the"
                    + " database schema and the HTTP API. Call this before answering any question about the"
                    + " application itself, and answer from what it returns rather than from memory. It does"
                    + " not hold the user's own data: for their URLs, their checks or their timeline, use the"
                    + " other tools.")
    public ToolResult searchKnowledge(
            @ToolParam(description = "What to look up, as a question or a phrase, for example"
                    + " 'how does the app decide a page changed'") String question,
            @ToolParam(required = false,
                    description = "Optional category to narrow the search to: architecture, business,"
                            + " database or api") String category) {
        try {
            List<KnowledgeRetrievalService.FoundChunk> found = this.retrieval.search(question, category);
            if (found.isEmpty()) {
                return ToolResult.ok(new NoMatch(question,
                        "Nothing in the documentation is close enough to this question."));
            }
            return ToolResult.ok(new Passages(question, found.stream()
                    .map(chunk -> new Passage(chunk.document(), chunk.heading(), chunk.text()))
                    .toList()));
        }
        catch (RuntimeException ex) {
            // The detail is for the operator, not the user: it can name the
            // collection and the host. The model is told only that it could not
            // look, so it does not present a failure as "no such documentation".
            log.warn("Knowledge retrieval failed", ex);
            return ToolResult.error("The documentation could not be searched right now."
                    + " Answer without it, and tell the user the check failed.");
        }
    }

    /** The passages that matched, best first. */
    public record Passages(String question, List<Passage> passages) {
    }

    /** One passage of the application's documentation. */
    public record Passage(String document, String heading, String text) {
    }

    /** The documentation has nothing to say about this. */
    public record NoMatch(String question, String reason) {
    }
}