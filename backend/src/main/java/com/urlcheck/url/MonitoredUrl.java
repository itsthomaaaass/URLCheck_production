package com.urlcheck.url;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class MonitoredUrl {

    private Long id;
    private Long userId;
    private String name;
    private String url;
    private String description;
    private LocalDateTime createdAt;

    // Monitoring state. Written by the scheduled checker; contentHash and
    // nextCheckAt stay internal and are not serialized to clients.
    private String contentHash;
    private String lastStatus;
    private Integer lastHttpStatus;
    private String lastErrorType;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime nextCheckAt;
    private Long changeCount;
    private Integer checkIntervalSeconds;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }


    @JsonIgnore
    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public void setLastHttpStatus(Integer lastHttpStatus) {
        this.lastHttpStatus = lastHttpStatus;
    }

    public String getLastErrorType() {
        return lastErrorType;
    }

    public void setLastErrorType(String lastErrorType) {
        this.lastErrorType = lastErrorType;
    }

    public LocalDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(LocalDateTime lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    @JsonIgnore
    public LocalDateTime getNextCheckAt() {
        return nextCheckAt;
    }

    public void setNextCheckAt(LocalDateTime nextCheckAt) {
        this.nextCheckAt = nextCheckAt;
    }

    public Long getChangeCount() {
        return changeCount;
    }

    public void setChangeCount(Long changeCount) {
        this.changeCount = changeCount;
    }

    public Integer getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public void setCheckIntervalSeconds(Integer checkIntervalSeconds) {
        this.checkIntervalSeconds = checkIntervalSeconds;
    }
}