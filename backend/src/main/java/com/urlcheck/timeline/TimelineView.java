package com.urlcheck.timeline;

import java.util.List;

/**
 * The timeline of one URL: the newest {@code limit} events plus the all-time
 * event count, which is larger than the stored row count once old events have
 * been pruned.
 */
public record TimelineView(Long urlId, Long totalCount, int limit, List<TimelineEvent> events) {
}