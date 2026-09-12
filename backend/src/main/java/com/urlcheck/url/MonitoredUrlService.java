package com.urlcheck.url;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.urlcheck.monitor.UrlCheckRequestedEvent;
import com.urlcheck.security.SsrfGuard;

@Service
public class MonitoredUrlService {

    private final MonitoredUrlMapper mapper;
    private final ApplicationEventPublisher events;

    public MonitoredUrlService(MonitoredUrlMapper mapper, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.events = events;
    }

    public List<MonitoredUrl> findAllForUser(Long userId) {
        return mapper.findAllByUserId(userId);
    }

    @Transactional
    public MonitoredUrl create(Long userId, MonitoredUrl monitoredUrl) {
        validate(monitoredUrl);
        monitoredUrl.setUserId(userId);
        mapper.insert(monitoredUrl);
        MonitoredUrl created = mapper.findByIdAndUserId(monitoredUrl.getId(), userId);
        events.publishEvent(new UrlCheckRequestedEvent(created.getId()));
        return created;
    }

    @Transactional
    public MonitoredUrl update(Long userId, Long id, MonitoredUrl monitoredUrl) {
        MonitoredUrl existing = mapper.findByIdAndUserId(id, userId);
        if (existing == null) {
            throw new NoSuchElementException("URL 不存在: id=" + id);
        }
        validate(monitoredUrl);
        monitoredUrl.setId(id);
        monitoredUrl.setUserId(userId);
        mapper.update(monitoredUrl);
        if (!existing.getUrl().equals(monitoredUrl.getUrl())) {
            // The stored history describes the old page, so the edit starts a
            // new one: drop the timeline and schedule a fresh baseline.
            mapper.deleteTimeline(id);
            mapper.resetMonitoringState(id, userId);
            events.publishEvent(new UrlCheckRequestedEvent(id));
        }
        return mapper.findByIdAndUserId(id, userId);
    }

    public MonitoredUrl delete(Long userId, Long id) {
        MonitoredUrl existing = mapper.findByIdAndUserId(id, userId);
        if (existing == null) {
            throw new NoSuchElementException("URL 不存在: id=" + id);
        }
        mapper.deleteByIdAndUserId(id, userId);
        return existing;
    }

    private void validate(MonitoredUrl monitoredUrl) {
        String name = monitoredUrl.getName();
        String url = monitoredUrl.getUrl();
        String description = monitoredUrl.getDescription();
        if (description == null) {
            description = "";
            monitoredUrl.setDescription(description);
        }

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("name 最长 100 个字符");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("url 必须以 http:// 或 https:// 开头");
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("url 格式不正确");
        }
        if (SsrfGuard.isBlockedHost(host)) {
            throw new IllegalArgumentException("url 指向内网地址，已被拒绝");
        }
        if (description.length() > 1000) {
            throw new IllegalArgumentException("description 最长 1000 个字符");
        }
    }
}
