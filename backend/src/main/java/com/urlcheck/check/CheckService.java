package com.urlcheck.check;

import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;

import com.urlcheck.url.MonitoredUrl;
import com.urlcheck.url.MonitoredUrlMapper;

/**
 * Runs an accessibility check for one of the current user's URLs.
 *
 * <p>Nothing is persisted: the check is computed and returned. The result is
 * therefore only as fresh as the last request, and "never checked" is a
 * client-side state. If the timeline module later needs to record outcomes,
 * this method is the single seam where that would happen.
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
        return urlChecker.check(monitoredUrl.getId(), monitoredUrl.getUrl());
    }
}
