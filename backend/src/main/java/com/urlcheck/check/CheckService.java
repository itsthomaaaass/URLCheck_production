package com.urlcheck.check;

import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;

import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlMapper;

/**
 * The manual, on-demand check.
 *
 * <p>Read-only by design: it probes the URL and reports what that probe means
 * against the stored baseline, but writes nothing. The timeline is maintained
 * exclusively by the scheduled checker, so a manual check can never add an
 * event, move the next scheduled check, or touch the stored hash.
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
     * Checks the stored URL with the given id, if it belongs to this user.
     *
     * @throws NoSuchElementException when no such URL exists for the user,
     *         which also covers URLs owned by somebody else
     */
    public CheckResult check(Long userId, Long urlId) {
        MonitoredUrl monitoredUrl = urlMapper.findByIdAndUserId(urlId, userId);
        if (monitoredUrl == null) {
            throw new NoSuchElementException("URL 不存在: id=" + urlId);
        }
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
}
