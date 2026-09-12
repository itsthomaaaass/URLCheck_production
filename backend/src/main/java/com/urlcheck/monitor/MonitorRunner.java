package com.urlcheck.monitor;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.urlcheck.check.ChangeBaseline;
import com.urlcheck.check.ChangeDetector;
import com.urlcheck.check.ProbeResult;
import com.urlcheck.check.UrlChecker;
import com.urlcheck.url.MonitoredUrl;

/**
 * Runs checks for the scheduler and for the create/edit hook.
 *
 * <p>A URL is claimed before it is probed, so a caller that lost the race does
 * nothing. That is what stops two backend instances - or the poller and a
 * create-time check - from recording the same change twice.
 */
@Service
public class MonitorRunner {

    private static final Logger log = LoggerFactory.getLogger(MonitorRunner.class);

    private final MonitorMapper monitorMapper;
    private final MonitorWriter writer;
    private final UrlChecker urlChecker;
    private final CheckProperties properties;

    public MonitorRunner(MonitorMapper monitorMapper, MonitorWriter writer, UrlChecker urlChecker,
            CheckProperties properties) {
        this.monitorMapper = monitorMapper;
        this.writer = writer;
        this.urlChecker = urlChecker;
        this.properties = properties;
    }
    /**
     * Checks one URL now, if it is due and nobody else took it first.
     *
     * @return true when this call owned the check and ran it
     */
    public boolean runIfDue(Long urlId) {
        if (monitorMapper.claim(urlId, properties.defaultIntervalSeconds()) != 1) {
            return false;
        }
        MonitoredUrl state = monitorMapper.findCheckState(urlId);
        if (state == null) {
            return false;
        }
        ChangeBaseline baseline = baselineOf(state);
        ProbeResult probe = urlChecker.probe(urlId, state.getUrl());
        writer.apply(state, probe, ChangeDetector.decide(baseline, probe), baseline);
        return true;
    }

    /** Checks the due URLs, at most {@code batchSize} per call. */
    public int runDueBatch() {
        List<MonitoredUrl> due = monitorMapper.findDue(properties.batchSize());
        int ran = 0;
        for (MonitoredUrl url : due) {
            try {
                if (runIfDue(url.getId())) {
                    ran++;
                }
            } catch (RuntimeException ex) {
                // The claim already moved next_check_at, so pull it back in
                // rather than leaving the URL unchecked for a whole interval.
                log.warn("check failed unexpectedly for url {}", url.getId(), ex);
                monitorMapper.retrySoon(url.getId());
            }
        }
        return ran;
    }

    static ChangeBaseline baselineOf(MonitoredUrl state) {
        return new ChangeBaseline(
                state.getContentHash(),
                state.getLastStatus(),
                state.getLastHttpStatus(),
                state.getLastErrorType());
    }
}