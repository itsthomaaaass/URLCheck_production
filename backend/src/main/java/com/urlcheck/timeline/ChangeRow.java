package com.urlcheck.timeline;

import java.time.LocalDateTime;

/** One persisted {@code changes} row, as read back by MyBatis. */
public class ChangeRow {

    private Long id;
    private Long changeNo;
    private LocalDateTime detectedAt;
    private String changeType;
    private String oldHash;
    private String newHash;
    private Integer httpStatus;
    private String errorType;
    private Long responseTimeMs;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChangeNo() {
        return changeNo;
    }

    public void setChangeNo(Long changeNo) {
        this.changeNo = changeNo;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }
    public void setDetectedAt(LocalDateTime detectedAt) {
        this.detectedAt = detectedAt;
    }

    public String getChangeType() {
        return changeType;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public String getOldHash() {
        return oldHash;
    }

    public void setOldHash(String oldHash) {
        this.oldHash = oldHash;
    }

    public String getNewHash() {
        return newHash;
    }

    public void setNewHash(String newHash) {
        this.newHash = newHash;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public Long getResponseTimeMs() {
        return responseTimeMs;
    }

    public void setResponseTimeMs(Long responseTimeMs) {
        this.responseTimeMs = responseTimeMs;
    }
}