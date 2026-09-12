package com.urlcheck.monitor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.urlcheck.check.ChangeBaseline;
import com.urlcheck.check.ChangeDecision;
import com.urlcheck.check.ChangeType;
import com.urlcheck.check.CheckStatus;
import com.urlcheck.check.ProbeResult;
import com.urlcheck.timeline.TimelineMapper;
import com.urlcheck.url.MonitoredUrl;

/**
 * Applies one finished check to the database: the URL's new state, and - when
 * the outcome is a real event - one timeline row plus the prune that keeps each
 * URL at a fixed number of rows.
 *
 * <p>The only writer of monitoring state and of {@code changes}. It runs in one
 * transaction so an event and the state it describes can never diverge.
 */
@Component
public class MonitorWriter {

    private final MonitorMapper monitorMapper;
    private final TimelineMapper timelineMapper;
    private final CheckProperties properties;

    public MonitorWriter(MonitorMapper monitorMapper, TimelineMapper timelineMapper,
            CheckProperties properties) {
        this.monitorMapper = monitorMapper;
        this.timelineMapper = timelineMapper;
        this.properties = properties;
    }
    @Transactional
    public void apply(MonitoredUrl state, ProbeResult probe, ChangeDecision decision,
            ChangeBaseline baseline) {
        // An outage is one event: it is recorded when the URL goes down, and
        // while it stays down another failed probe only refreshes the stored
        // reason. Comparing the failure signatures instead would append a row
        // every time a site that never recovered flapped between, say, a
        // timeout and a 503.
        boolean event = decision.type() != null
                && !(decision.type() == ChangeType.UNAVAILABLE && baseline.lastStateDown());
        if (event) {
            timelineMapper.insertEvent(
                    state.getId(),
                    decision.type().name(),
                    state.getContentHash(),
                    probe.contentHash(),
                    probe.httpStatus(),
                    errorTypeOf(probe),
                    probe.responseTimeMs());
            timelineMapper.prune(state.getId(), properties.keepNonOriginal());
        }
        if (probe.status() == CheckStatus.UP) {
            monitorMapper.markUp(state.getId(), probe.contentHash(), probe.httpStatus(), event ? 1 : 0);
        } else {
            monitorMapper.markDown(
                    state.getId(), probe.httpStatus(), errorTypeOf(probe), event ? 1 : 0);
        }
    }

    private static String errorTypeOf(ProbeResult probe) {
        return probe.errorType() == null ? null : probe.errorType().name();
    }
}
