package com.urlcheck.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ChangeDetectorTest {

    private static final ChangeBaseline NEVER_CHECKED = new ChangeBaseline(null, null);

    @Test
    void firstSuccessfulProbeBecomesTheBaseline() {
        ChangeDecision decision = ChangeDetector.decide(NEVER_CHECKED, up("aaa"));
        assertThat(decision.type()).isEqualTo(ChangeType.FIRST_CHECK);
        assertThat(decision.changed()).isTrue();
        assertThat(decision.status()).isEqualTo(CheckStatus.UP);
    }

    @Test
    void identicalHashIsNotAnEvent() {
        ChangeDecision decision = ChangeDetector.decide(
                new ChangeBaseline("aaa", "UP"), up("aaa"));
        assertThat(decision.type()).isNull();
        assertThat(decision.changed()).isFalse();
    }

    @Test
    void differentHashIsAContentChange() {
        ChangeDecision decision = ChangeDetector.decide(
                new ChangeBaseline("aaa", "UP"), up("bbb"));
        assertThat(decision.type()).isEqualTo(ChangeType.CONTENT_CHANGED);
        assertThat(decision.changed()).isTrue();
    }

    @Test
    void aFailedProbeReportsNoChangeVerdict() {
        ChangeDecision decision = ChangeDetector.decide(
                new ChangeBaseline("aaa", "UP"), httpError(403));
        assertThat(decision.type()).isEqualTo(ChangeType.UNAVAILABLE);
        assertThat(decision.status()).isEqualTo(CheckStatus.DOWN);
        assertThat(decision.changed()).isNull();
    }

    @Test
    void successAfterAFailureIsARecoveryEvenWithoutAStoredHash() {
        ChangeDecision decision = ChangeDetector.decide(
                new ChangeBaseline(null, "DOWN"), up("aaa"));
        assertThat(decision.type()).isEqualTo(ChangeType.RECOVERED);
        assertThat(decision.changed()).isTrue();
    }

    @Test
    void successAfterAFailureWithTheSameBodyIsStillARecovery() {
        ChangeDecision decision = ChangeDetector.decide(
                new ChangeBaseline("aaa", "DOWN"), up("aaa"));
        assertThat(decision.type()).isEqualTo(ChangeType.RECOVERED);
        assertThat(decision.status()).isEqualTo(CheckStatus.UP);
        assertThat(decision.changed()).isFalse();
    }

    @Test
    void successAfterAFailureWithADifferentBodyIsARecoveryNotAContentChange() {
        ChangeDecision decision = ChangeDetector.decide(
                new ChangeBaseline("aaa", "DOWN"), up("bbb"));
        assertThat(decision.type()).isEqualTo(ChangeType.RECOVERED);
        assertThat(decision.changed()).isTrue();
    }

    private static ProbeResult up(String hash) {
        return new ProbeResult(1L, LocalDateTime.now(), CheckStatus.UP, 200, 12,
                "https://example.com/", null, hash);
    }

    private static ProbeResult httpError(int status) {
        return new ProbeResult(1L, LocalDateTime.now(), CheckStatus.DOWN, status, 12,
                "https://example.com/", CheckErrorType.HTTP_ERROR, null);
    }
}
