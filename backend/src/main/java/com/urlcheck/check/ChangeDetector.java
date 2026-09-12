package com.urlcheck.check;

import java.util.Objects;

/**
 * Decides what one probe means. Free of persistence: the manual check uses the
 * same rule to describe the outcome, the scheduled checker to record it, so
 * the two can never drift apart.
 */
public final class ChangeDetector {

    public static ChangeDecision decide(ChangeBaseline baseline, ProbeResult probe) {
        if (probe.errorType() != null) {
            return new ChangeDecision(ChangeType.UNAVAILABLE, CheckStatus.DOWN, null);
        }
        if (baseline.lastStateDown()) {
            return new ChangeDecision(
                    ChangeType.RECOVERED,
                    CheckStatus.UP,
                    !Objects.equals(baseline.contentHash(), probe.contentHash()));
        }
        if (baseline.contentHash() == null) {
            return new ChangeDecision(ChangeType.FIRST_CHECK, CheckStatus.UP, Boolean.TRUE);
        }
        boolean sameHash = Objects.equals(baseline.contentHash(), probe.contentHash());
        return sameHash
                ? new ChangeDecision(null, CheckStatus.UP, Boolean.FALSE)
                : new ChangeDecision(ChangeType.CONTENT_CHANGED, CheckStatus.UP, Boolean.TRUE);
    }
    /**
     * True when the probe failed exactly like the stored state, so another
     * event would only repeat the previous one. Storage policy, but it reasons
     * about check semantics, so it lives next to the rule above.
     */
    public static boolean sameFailureAsLast(ChangeBaseline baseline, ProbeResult probe) {
        if (probe.errorType() == null || !baseline.lastStateDown()) {
            return false;
        }
        return Objects.equals(baseline.lastHttpStatus(), probe.httpStatus())
                && Objects.equals(baseline.lastErrorType(), probe.errorType().name());
    }

    private ChangeDetector() {
    }
}
