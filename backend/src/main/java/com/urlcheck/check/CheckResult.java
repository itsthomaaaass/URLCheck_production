package com.urlcheck.check;

import java.time.LocalDateTime;

/**
 * Outcome of one accessibility check, as returned to the client.
 *
 * <p>{@code finalUrl} and {@code errorType} are null when they do not apply.
 * {@code contentHash}, {@code changed} and {@code changeType} say what the
 * probe means against the stored baseline; the manual check computes them for
 * display only and writes nothing. {@code changed} is null exactly when the
 * probe produced no usable response.
 */
public record CheckResult(
        Long urlId,
        LocalDateTime checkedAt,
        CheckStatus status,
        Integer httpStatus,
        long responseTimeMs,
        String finalUrl,
        CheckErrorType errorType,
        String contentHash,
        Boolean changed,
        ChangeType changeType) {
}