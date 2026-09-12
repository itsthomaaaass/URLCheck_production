package com.urlcheck.check;

/**
 * Stored state a probe is compared against: the last good body hash and
 * whether the previous check failed. The detail of a failure (its HTTP status,
 * its error type) is kept on the URL row for display, but it does not decide
 * whether the next failed check is a new event.
 */
public record ChangeBaseline(String contentHash, String lastStatus) {

    public boolean lastStateDown() {
        return "DOWN".equals(lastStatus);
    }
}
