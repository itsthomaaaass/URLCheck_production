package com.urlcheck.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.urlcheck.check.ChangeBaseline;
import com.urlcheck.check.ChangeDetector;
import com.urlcheck.check.CheckErrorType;
import com.urlcheck.check.CheckStatus;
import com.urlcheck.check.ProbeResult;
import com.urlcheck.timeline.TimelineMapper;
import com.urlcheck.url.MonitoredUrl;

class MonitorWriterTest {

    private final MonitorMapper monitorMapper = mock(MonitorMapper.class);
    private final TimelineMapper timelineMapper = mock(TimelineMapper.class);
    private final CheckProperties properties = new CheckProperties(true, 15, 20, 3600, 20, 10, true);
    private final MonitorWriter writer = new MonitorWriter(monitorMapper, timelineMapper, properties);

    @Test
    void recordsAContentChangeAndPrunesToTheAnchorPlusNine() {
        MonitoredUrl state = state("old", "UP", 200, null);
        ProbeResult probe = up("new");
        apply(state, probe);

        verify(timelineMapper).insertEvent(7L, "CONTENT_CHANGED", "old", "new", 200, null, 12L);
        verify(timelineMapper).prune(7L, 9);
        verify(monitorMapper).markUp(7L, "new", 200, 1);
    }

    @Test
    void anUnchangedProbeWritesNoEvent() {
        MonitoredUrl state = state("same", "UP", 200, null);
        ProbeResult probe = up("same");
        apply(state, probe);

        verifyNoEventWritten();
        verify(monitorMapper).markUp(7L, "same", 200, 0);
    }

    @Test
    void aRepeatedFailureIsNotRecordedAgain() {
        MonitoredUrl state = state("kept", "DOWN", 403, "HTTP_ERROR");
        ProbeResult probe = httpError(403);
        apply(state, probe);

        verifyNoEventWritten();
        verify(monitorMapper).markDown(7L, 403, "HTTP_ERROR", 0);
    }

    @Test
    void aFailureWhileTheUrlStaysDownIsNotRecordedAgain() {
        MonitoredUrl state = state("kept", "DOWN", 403, "HTTP_ERROR");
        ProbeResult probe = timeout();
        apply(state, probe);

        verifyNoEventWritten();
        verify(monitorMapper).markDown(7L, null, "TIMEOUT", 0);
    }

    @Test
    void aRepeatedTimeoutIsNotRecordedAgain() {
        MonitoredUrl state = state("kept", "DOWN", null, "TIMEOUT");
        ProbeResult probe = timeout();
        apply(state, probe);

        verifyNoEventWritten();
        verify(monitorMapper).markDown(7L, null, "TIMEOUT", 0);
    }

    @Test
    void theFirstFailureAfterAHealthyCheckIsRecorded() {
        MonitoredUrl state = state("kept", "UP", 200, null);
        ProbeResult probe = timeout();
        apply(state, probe);

        verify(timelineMapper).insertEvent(7L, "UNAVAILABLE", "kept", null, null, "TIMEOUT", 5000L);
        verify(timelineMapper).prune(7L, 9);
        verify(monitorMapper).markDown(7L, null, "TIMEOUT", 1);
    }

    @Test
    void aFailureKeepsTheLastGoodHash() {
        MonitoredUrl state = state("last-good", "UP", 200, null);
        ProbeResult probe = httpError(503);
        apply(state, probe);

        verify(monitorMapper).markDown(7L, 503, "HTTP_ERROR", 1);
        assertThat(state.getContentHash()).isEqualTo("last-good");
        verify(monitorMapper, never()).markUp(anyLong(), any(), any(), anyInt());
    }

    private void apply(MonitoredUrl state, ProbeResult probe) {
        ChangeBaseline baseline = MonitorRunner.baselineOf(state);
        writer.apply(state, probe, ChangeDetector.decide(baseline, probe), baseline);
    }

    private void verifyNoEventWritten() {
        verify(timelineMapper, never())
                .insertEvent(anyLong(), any(), any(), any(), any(), any(), anyLong());
        verify(timelineMapper, never()).prune(anyLong(), anyInt());
    }

    private static MonitoredUrl state(String hash, String lastStatus, Integer httpStatus, String errorType) {
        MonitoredUrl url = new MonitoredUrl();
        url.setId(7L);
        url.setUrl("https://example.com");
        url.setContentHash(hash);
        url.setLastStatus(lastStatus);
        url.setLastHttpStatus(httpStatus);
        url.setLastErrorType(errorType);
        return url;
    }

    private static ProbeResult up(String hash) {
        return new ProbeResult(7L, LocalDateTime.now(), CheckStatus.UP, 200, 12,
                "https://example.com/", null, hash);
    }

    private static ProbeResult httpError(int status) {
        return new ProbeResult(7L, LocalDateTime.now(), CheckStatus.DOWN, status, 12,
                "https://example.com/", CheckErrorType.HTTP_ERROR, null);
    }

    private static ProbeResult timeout() {
        return new ProbeResult(7L, LocalDateTime.now(), CheckStatus.DOWN, null, 5000,
                null, CheckErrorType.TIMEOUT, null);
    }
}
