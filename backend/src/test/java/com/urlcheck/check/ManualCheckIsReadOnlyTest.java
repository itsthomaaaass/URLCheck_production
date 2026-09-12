package com.urlcheck.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlMapper;

/**
 * Guards the separation the design rests on: a manual check reads the stored
 * baseline and reports what it means, but writes nothing at all, so it cannot
 * disturb the scheduler, the timeline or the stored hash.
 */
class ManualCheckIsReadOnlyTest {

    private final MonitoredUrlMapper urlMapper = mock(MonitoredUrlMapper.class);
    private final UrlChecker urlChecker = mock(UrlChecker.class);
    private final CheckService service = new CheckService(urlMapper, urlChecker);

    @Test
    void describesTheChangeAgainstTheStoredBaselineWithoutWriting() {
        MonitoredUrl stored = new MonitoredUrl();
        stored.setId(7L);
        stored.setUrl("https://example.com");
        stored.setContentHash("old");
        stored.setLastStatus("UP");
        when(urlMapper.findByIdAndUserId(7L, 1L)).thenReturn(stored);
        when(urlChecker.probe(7L, "https://example.com")).thenReturn(new ProbeResult(
                7L, LocalDateTime.now(), CheckStatus.UP, 200, 12,
                "https://example.com/", null, "new"));

        CheckResult result = service.check(1L, 7L);

        assertThat(result.changed()).isTrue();
        assertThat(result.changeType()).isEqualTo(ChangeType.CONTENT_CHANGED);
        assertThat(result.contentHash()).isEqualTo("new");
        verify(urlMapper).findByIdAndUserId(7L, 1L);
        verify(urlMapper, never()).insert(any());
        verify(urlMapper, never()).update(any());
        verify(urlMapper, never()).deleteByIdAndUserId(any(), any());
        verify(urlMapper, never()).resetMonitoringState(any(), any());
        verify(urlMapper, never()).deleteTimeline(any());
    }
}