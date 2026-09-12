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
    private ChangeDetector() {
    }
}
