package com.urlcheck.check;

import java.time.LocalDateTime;

/**
 * Outcome of one probe, including the body hash whenever a body could be read.
 *
 * <p>Never persisted here: the manual check returns it, the scheduled checker
 * turns it into timeline state.
 */
public record ProbeResult(
        Long urlId,
        LocalDateTime checkedAt,
        CheckStatus status,
        Integer httpStatus,
        long responseTimeMs,
        String finalUrl,
        CheckErrorType errorType,
        String contentHash) {
}