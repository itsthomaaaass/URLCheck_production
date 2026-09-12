package com.urlcheck.timeline;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;

import com.urlcheck.check.ChangeType;
import com.urlcheck.check.CheckStatus;
import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlMapper;

/** Read side of the timeline; events are appended by the scheduled checker. */
@Service
public class TimelineService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final TimelineMapper timelineMapper;
    private final MonitoredUrlMapper urlMapper;

    public TimelineService(TimelineMapper timelineMapper, MonitoredUrlMapper urlMapper) {
        this.timelineMapper = timelineMapper;
        this.urlMapper = urlMapper;
    }
    /**
     * Timeline of one of the current user's URLs, newest event first.
     *
     * @throws NoSuchElementException when the URL does not exist for this user,
     *         which also covers URLs owned by somebody else
     */
    public TimelineView findForUser(Long userId, Long urlId, Integer limit) {
        MonitoredUrl url = urlMapper.findByIdAndUserId(urlId, userId);
        if (url == null) {
            throw new NoSuchElementException("URL 不存在: id=" + urlId);
        }
        int effective = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        List<TimelineEvent> events = timelineMapper.findByUrlId(urlId, effective).stream()
                .map(TimelineService::toEvent)
                .toList();
        long total = url.getChangeCount() == null ? events.size() : url.getChangeCount();
        return new TimelineView(urlId, total, effective, events);
    }

    private static TimelineEvent toEvent(ChangeRow row) {
        return new TimelineEvent(
                row.getId(),
                row.getChangeNo(),
                row.getDetectedAt(),
                row.getChangeType(),
                statusOf(row.getChangeType()),
                row.getHttpStatus(),
                row.getErrorType(),
                row.getResponseTimeMs(),
                row.getOldHash(),
                row.getNewHash());
    }

    /** UNAVAILABLE is the only event that means the site was not reached. */
    private static String statusOf(String changeType) {
        return ChangeType.UNAVAILABLE.name().equals(changeType)
                ? CheckStatus.DOWN.name()
                : CheckStatus.UP.name();
    }
}