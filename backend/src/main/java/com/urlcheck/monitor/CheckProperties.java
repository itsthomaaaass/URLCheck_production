package com.urlcheck.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scheduling knobs, bound from {@code app.check.*} and therefore from the
 * environment, so one deployment can poll hourly and another every minute
 * without a rebuild.
 *
 * @param enabled                run the scheduler at all (off in tests)
 * @param tickSeconds            how often the scheduler looks for due URLs
 * @param initialDelaySeconds    wait before the first tick, to let startup settle
 * @param defaultIntervalSeconds per-URL cadence used when a URL has no override
 * @param batchSize              URLs checked per tick, bounding one burst
 * @param retentionPerUrl        timeline rows kept per URL, anchor included
 * @param onCreate               check a newly created URL immediately
 */
@ConfigurationProperties(prefix = "app.check")
public record CheckProperties(
        boolean enabled,
        long tickSeconds,
        long initialDelaySeconds,
        long defaultIntervalSeconds,
        int batchSize,
        int retentionPerUrl,
        boolean onCreate) {

    /** Non-anchor timeline rows kept; the first check is protected separately. */
    public int keepNonOriginal() {
        return Math.max(0, retentionPerUrl - 1);
    }
}