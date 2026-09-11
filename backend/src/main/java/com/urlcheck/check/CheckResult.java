package com.urlcheck.check;

import java.time.LocalDateTime;

/**
 * Outcome of one accessibility check, as returned to the client.
 *
 * <p>Nothing is written to the database: the result exists only for the
 * duration of the response. {@code finalUrl} and {@code errorType} are null
 * when they do not apply.
 */
public record CheckResult(
        Long urlId,
        LocalDateTime checkedAt,
        CheckStatus status,
        Integer httpStatus,
        long responseTimeMs,
        String finalUrl,
        CheckErrorType errorType) {
}
