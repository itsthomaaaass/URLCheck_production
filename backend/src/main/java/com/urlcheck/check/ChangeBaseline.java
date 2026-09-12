package com.urlcheck.check;

/**
 * Stored state a probe is compared against: the last good body hash plus the
 * signature of the previous failure, if there was one.
 */
public record ChangeBaseline(
        String contentHash,
        String lastStatus,
        Integer lastHttpStatus,
        String lastErrorType) {

    public boolean lastStateDown() {
        return "DOWN".equals(lastStatus);
    }
}