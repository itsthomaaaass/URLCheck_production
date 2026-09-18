package com.urlcheck.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.urlcheck.ai.deletion.PendingDeletions;
import com.urlcheck.check.CheckErrorType;
import com.urlcheck.check.ProbeResult;
import com.urlcheck.check.CheckService;
import com.urlcheck.check.CheckStatus;
import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlService;

/**
 * The tool layer is the boundary the design rests on: it hands the model facts
 * about one user's URLs and nothing else, it delegates every rule to the
 * existing services, and it never deletes anything on its own.
 */
class MonitoredUrlToolsTest {

    private static final Long USER_ID = 1L;

    private final MonitoredUrlService urlService = mock(MonitoredUrlService.class);
    private final CheckService checkService = mock(CheckService.class);

    @Test
    void listsTheUrlsTheServiceReturnsForThisUser() {
        when(urlService.findAllForUser(USER_ID)).thenReturn(List.of(stored(7L, "Example", "https://example.com")));

        ToolResult result = tools(10).listUrls();

        assertThat(result.error()).isNull();
        assertThat(result.data()).isEqualTo(List.of(new MonitoredUrlTools.UrlSummary(
                7L, "Example", "https://example.com", "note", "UP", 200, "2026-09-17T10:00", 2L)));
    }

    @Test
    void reportsAccessibilityAndStopsAtTheConfiguredCap() {
        when(urlService.findAllForUser(USER_ID)).thenReturn(List.of(
                stored(7L, "Example", "https://example.com"),
                stored(8L, "University", "https://university.edu")));
        when(checkService.probe(USER_ID, 7L)).thenReturn(reachable(7L));

        MonitoredUrlTools.CheckReport report = (MonitoredUrlTools.CheckReport) tools(1).checkUrls().data();

        assertThat(report.totalUrls()).isEqualTo(2);
        assertThat(report.truncated()).isTrue();
        assertThat(report.results()).hasSize(1);
        assertThat(report.results().get(0).accessible()).isTrue();
        verify(checkService, never()).probe(USER_ID, 8L);
    }

    @Test
    void reportsAnUnreachableUrlAsNotAccessible() {
        when(urlService.findAllForUser(USER_ID)).thenReturn(List.of(stored(8L, "University", "https://university.edu")));
        when(checkService.probe(USER_ID, 8L)).thenReturn(unreachable(8L, CheckErrorType.DNS_ERROR));

        MonitoredUrlTools.CheckReport report = (MonitoredUrlTools.CheckReport) tools(10).checkUrls().data();

        assertThat(report.truncated()).isFalse();
        assertThat(report.results().get(0).accessible()).isFalse();
        assertThat(report.results().get(0).errorType()).isEqualTo("DNS_ERROR");
    }

    /**
     * The assistant's check must stay exactly as harmless as the manual one: it
     * probes through the read-only CheckService and never reaches the
     * baseline-comparing check() or any write. A live look therefore cannot
     * report a timeline change, and cannot move one either. Only the scheduled
     * checker writes that state (ManualCheckIsReadOnlyTest pins the service side
     * of the same rule).
     */
    @Test
    void checksOnlyThroughTheReadOnlyProbe() {
        when(urlService.findAllForUser(USER_ID)).thenReturn(List.of(
                stored(7L, "Example", "https://example.com"),
                stored(8L, "University", "https://university.edu")));
        when(checkService.probe(USER_ID, 7L)).thenReturn(reachable(7L));
        when(checkService.probe(USER_ID, 8L)).thenReturn(reachable(8L));

        MonitoredUrlTools.CheckReport report = (MonitoredUrlTools.CheckReport) tools(10).checkUrls().data();

        assertThat(report.results()).extracting(MonitoredUrlTools.UrlStatus::status).containsOnly("UP");
        verify(checkService).probe(USER_ID, 7L);
        verify(checkService).probe(USER_ID, 8L);
        // check() is the one that reads the stored baseline and produces a
        // change verdict; the assistant's tool must never use it.
        verify(checkService, never()).check(any(), anyLong());
        verify(urlService, never()).create(any(), any());
        verify(urlService, never()).update(any(), any(), any());
        verify(urlService, never()).delete(anyLong(), anyLong());
    }

    @Test
    void createsThroughTheExistingService() {
        when(urlService.create(eq(USER_ID), any())).thenReturn(stored(9L, "New", "https://new.example"));

        ToolResult result = tools(10).createUrl("New", "https://new.example", null);

        assertThat(result.error()).isNull();
        assertThat(result.data()).isInstanceOf(MonitoredUrlTools.UrlSummary.class);
        assertThat(((MonitoredUrlTools.UrlSummary) result.data()).id()).isEqualTo(9L);
    }

    @Test
    void surfacesTheApplicationsOwnValidationFailureAsData() {
        when(urlService.create(eq(USER_ID), any()))
                .thenThrow(new IllegalArgumentException("url 必须以 http:// 或 https:// 开头"));

        ToolResult result = tools(10).createUrl("Bad", "ftp://example.com", null);

        assertThat(result.data()).isNull();
        assertThat(result.error()).isEqualTo("url 必须以 http:// 或 https:// 开头");
    }

    @Test
    void proposesADeletionWithoutPerformingIt() {
        when(urlService.findAllForUser(USER_ID)).thenReturn(List.of(stored(7L, "Example", "https://example.com")));
        MonitoredUrlTools tools = tools(10);

        ToolResult result = tools.deleteUrl(7L);

        assertThat(result.data()).isEqualTo(new MonitoredUrlTools.DeletionProposal(7L, "Example", "https://example.com"));
        assertThat(tools.proposedDeletions())
                .containsExactly(new PendingDeletions.Item(7L, "Example", "https://example.com"));
        verify(urlService, never()).delete(anyLong(), anyLong());
    }

    @Test
    void proposesEachUrlOnlyOnce() {
        when(urlService.findAllForUser(USER_ID)).thenReturn(List.of(stored(7L, "Example", "https://example.com")));
        MonitoredUrlTools tools = tools(10);

        tools.deleteUrl(7L);
        tools.deleteUrl(7L);

        assertThat(tools.proposedDeletions()).hasSize(1);
    }

    @Test
    void refusesToProposeAUrlTheUserDoesNotOwn() {
        when(urlService.findAllForUser(USER_ID)).thenReturn(List.of(stored(7L, "Example", "https://example.com")));
        MonitoredUrlTools tools = tools(10);

        ToolResult result = tools.deleteUrl(99L);

        assertThat(result.error()).contains("99");
        assertThat(tools.proposedDeletions()).isEmpty();
    }

    private MonitoredUrlTools tools(int maxUrlsPerCheck) {
        return new MonitoredUrlTools(USER_ID, urlService, checkService, maxUrlsPerCheck);
    }

    private static ProbeResult reachable(long urlId) {
        return new ProbeResult(urlId, LocalDateTime.now(), CheckStatus.UP, 200, 12L,
                "https://example.com/", null, "hash");
    }

    private static ProbeResult unreachable(long urlId, CheckErrorType errorType) {
        return new ProbeResult(urlId, LocalDateTime.now(), CheckStatus.DOWN, null, 30L,
                null, errorType, null);
    }

    private static MonitoredUrl stored(long id, String name, String address) {
        MonitoredUrl url = new MonitoredUrl();
        url.setId(id);
        url.setName(name);
        url.setUrl(address);
        url.setDescription("note");
        url.setLastStatus("UP");
        url.setLastHttpStatus(200);
        url.setLastCheckedAt(LocalDateTime.of(2026, 9, 17, 10, 0));
        url.setChangeCount(2L);
        return url;
    }
}
