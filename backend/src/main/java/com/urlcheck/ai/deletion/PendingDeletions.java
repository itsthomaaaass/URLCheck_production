package com.urlcheck.ai.deletion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * Holds the deletions the assistant has proposed but not performed.
 *
 * <p>A batch lives in the HTTP session, so it belongs to the user who asked for
 * it, cannot be reached from another session, and dies with that session. The
 * token is only a handle: the ids a confirmation acts on come from this
 * server-side copy, never from the request body and never from the model, so a
 * caller cannot talk the backend into deleting something the assistant never
 * proposed.
 */
@Component
public class PendingDeletions {

    /** One URL a confirmation would remove. */
    public record Item(long urlId, String name, String url) {
    }

    /** One confirmation the user still has to give. */
    public record Batch(String token, List<Item> items) {
    }

    private static final String SESSION_KEY = "ai.pendingDeletion";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Records a proposal and returns the handle the client may confirm with.
     * Any earlier proposal is replaced: only the most recent one can be carried
     * out.
     */
    public Batch open(HttpSession session, List<Item> items) {
        byte[] handle = new byte[16];
        RANDOM.nextBytes(handle);
        Batch batch = new Batch(HexFormat.of().formatHex(handle), List.copyOf(items));
        session.setAttribute(SESSION_KEY, batch);
        return batch;
    }

    /**
     * Consumes the pending proposal, so a confirmation can be used only once.
     *
     * @return the batch when the token matches the one this session is holding,
     *         otherwise empty
     */
    public Optional<Batch> take(HttpSession session, String token) {
        Object stored = session.getAttribute(SESSION_KEY);
        session.removeAttribute(SESSION_KEY);
        if (!(stored instanceof Batch batch) || token == null) {
            return Optional.empty();
        }
        boolean matches = MessageDigest.isEqual(
                batch.token().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        return matches ? Optional.of(batch) : Optional.empty();
    }
}
