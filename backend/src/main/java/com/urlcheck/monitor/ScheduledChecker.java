package com.urlcheck.monitor;

import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks for due checks. How often one URL is checked lives in the database; this
 * only decides how often the database is asked. fixedDelay means a slow batch
 * delays the next tick instead of overlapping it.
 */
@Component
@ConditionalOnProperty(prefix = "app.check", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ScheduledChecker {

    private static final Logger log = LoggerFactory.getLogger(ScheduledChecker.class);

    private final MonitorRunner runner;
    private final CheckProperties properties;

    public ScheduledChecker(MonitorRunner runner, CheckProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.check.tick-seconds:15}",
            initialDelayString = "${app.check.initial-delay-seconds:20}",
            timeUnit = TimeUnit.SECONDS)
    public void tick() {
        int ran = runner.runDueBatch();
        if (ran > 0) {
            log.info("checked {} url(s)", ran);
        }
    }

    @PostConstruct
    void logConfiguration() {
        log.info("scheduled checks enabled={} interval={}s tick={}s batch={} retention={}",
                properties.enabled(), properties.defaultIntervalSeconds(),
                properties.tickSeconds(), properties.batchSize(), properties.retentionPerUrl());
        if (properties.defaultIntervalSeconds() < properties.tickSeconds()) {
            log.warn("CHECK_INTERVAL_SECONDS ({}) is below CHECK_SCHEDULER_TICK_SECONDS ({});"
                    + " a URL cannot be checked more often than once per tick",
                    properties.defaultIntervalSeconds(), properties.tickSeconds());
        }
    }
}