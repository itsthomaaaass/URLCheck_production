package com.urlcheck.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlMapper;

class TimelineServiceTest {

    private final TimelineMapper timelineMapper = mock(TimelineMapper.class);
    private final MonitoredUrlMapper urlMapper = mock(MonitoredUrlMapper.class);
    private final TimelineService service = new TimelineService(timelineMapper, urlMapper);

    @Test
    void returnsEventsAndTheAllTimeCount() {
        when(urlMapper.findByIdAndUserId(7L, 1L)).thenReturn(ownedUrl(23L));
        when(timelineMapper.findByUrlId(7L, 10))
                .thenReturn(List.of(row(9L, "CONTENT_CHANGED", 200, null)));

        TimelineView view = service.findForUser(1L, 7L, null);

        assertThat(view.urlId()).isEqualTo(7L);
        assertThat(view.totalCount()).isEqualTo(23L);
        assertThat(view.limit()).isEqualTo(10);
        assertThat(view.events()).hasSize(1);
        assertThat(view.events().get(0).changeNo()).isEqualTo(9L);
        assertThat(view.events().get(0).status()).isEqualTo("UP");
        assertThat(view.events().get(0).changeType()).isEqualTo("CONTENT_CHANGED");
    }

    @Test
    void reportsAnUnavailableEventAsDown() {
        when(urlMapper.findByIdAndUserId(7L, 1L)).thenReturn(ownedUrl(1L));
        when(timelineMapper.findByUrlId(7L, 10))
                .thenReturn(List.of(row(1L, "UNAVAILABLE", 403, "HTTP_ERROR")));

        TimelineView view = service.findForUser(1L, 7L, null);

        assertThat(view.events().get(0).status()).isEqualTo("DOWN");
        assertThat(view.events().get(0).httpStatus()).isEqualTo(403);
        assertThat(view.events().get(0).errorType()).isEqualTo("HTTP_ERROR");
    }

    @Test
    void clampsARequestedLimitToTheMaximum() {
        when(urlMapper.findByIdAndUserId(7L, 1L)).thenReturn(ownedUrl(0L));
        when(timelineMapper.findByUrlId(7L, 100)).thenReturn(List.of());

        service.findForUser(1L, 7L, 500);

        verify(timelineMapper).findByUrlId(7L, 100);
    }

    @Test
    void refusesAUrlOwnedBySomebodyElse() {
        when(urlMapper.findByIdAndUserId(7L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> service.findForUser(1L, 7L, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static MonitoredUrl ownedUrl(long changeCount) {
        MonitoredUrl url = new MonitoredUrl();
        url.setId(7L);
        url.setChangeCount(changeCount);
        return url;
    }

    private static ChangeRow row(long changeNo, String changeType, Integer httpStatus, String errorType) {
        ChangeRow row = new ChangeRow();
        row.setId(100L);
        row.setChangeNo(changeNo);
        row.setDetectedAt(LocalDateTime.now());
        row.setChangeType(changeType);
        row.setHttpStatus(httpStatus);
        row.setErrorType(errorType);
        row.setResponseTimeMs(12L);
        return row;
    }
}