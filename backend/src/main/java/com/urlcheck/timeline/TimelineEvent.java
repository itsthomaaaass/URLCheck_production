package com.urlcheck.timeline;

import java.time.LocalDateTime;

/**
 * One timeline entry as returned to the client. The hashes are included so a
 * curious caller can see what changed; the UI does not need them.
 *
 * @param status UP when the check reached the site, DOWN when it did not,
 *               derived from {@code changeType} rather than stored
 */
public record TimelineEvent(
        Long id,
        Long changeNo,
        LocalDateTime detectedAt,
        String changeType,
        String status,
        Integer httpStatus,
        String errorType,
        Long responseTimeMs,
        String oldHash,
        String newHash) {
}