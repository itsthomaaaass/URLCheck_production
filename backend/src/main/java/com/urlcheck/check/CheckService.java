package com.urlcheck.check;

import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;

import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlMapper;

/**
 * The on-demand look at one stored URL.
 *
 * <p>Read-only by design: both operations probe the URL and report what the
 * probe saw, and neither writes. The timeline is maintained exclusively by the
 * scheduled checker, so a look from the URL list or from the assistant can
 * never add an event, move the next scheduled check, or touch the stored hash.
 *
 * <ul>
 *   <li>{@link #check} also says what the probe means against the stored
 *       baseline, which is what the manual-check endpoint reports.</li>
 *   <li>{@link #probe} stops at the probe itself, so its caller holds no change
 *       verdict that could be read as, or later confused with, a timeline
 *       update. That is the one the assistant's check tool uses.</li>
 * </ul>
 */
@Service
public class CheckService {

    private final MonitoredUrlMapper urlMapper;
    private final UrlChecker urlChecker;

    public CheckService(MonitoredUrlMapper urlMapper, UrlChecker urlChecker) {
        this.urlMapper = urlMapper;
        this.urlChecker = urlChecker;
    }
    /**
     * Checks the stored URL with the given id, if it belongs to this user, and
     * reports what the probe means against the stored baseline.
     *
     * @throws NoSuchElementException when no such URL exists for the user,
     *         which also covers URLs owned by somebody else
     */
    public CheckResult check(Long userId, Long urlId) {
        MonitoredUrl monitoredUrl = findOwned(userId, urlId);
        ProbeResult probe = urlChecker.probe(monitoredUrl.getId(), monitoredUrl.getUrl());
        ChangeBaseline baseline = new ChangeBaseline(
                monitoredUrl.getContentHash(),
                monitoredUrl.getLastStatus());
        ChangeDecision decision = ChangeDetector.decide(baseline, probe);
        return new CheckResult(
                probe.urlId(),
                probe.checkedAt(),
                probe.status(),
                probe.httpStatus(),
                probe.responseTimeMs(),
                probe.finalUrl(),
                probe.errorType(),
                probe.contentHash(),
                decision.changed(),
                decision.type());
    }

    /**
     * Probes the stored URL with the given id, if it belongs to this user, and
     * reports only what the probe saw: no comparison with the stored baseline,
     * and so no change verdict of any kind.
     *
     * @throws NoSuchElementException when no such URL exists for the user,
     *         which also covers URLs owned by somebody else
     */
    public ProbeResult probe(Long userId, Long urlId) {
        MonitoredUrl monitoredUrl = findOwned(userId, urlId);
        return urlChecker.probe(monitoredUrl.getId(), monitoredUrl.getUrl());
    }

    private MonitoredUrl findOwned(Long userId, Long urlId) {
        MonitoredUrl monitoredUrl = urlMapper.findByIdAndUserId(urlId, userId);
        if (monitoredUrl == null) {
            throw new NoSuchElementException("URL 不存在: id=" + urlId);
        }
        return monitoredUrl;
    }
}