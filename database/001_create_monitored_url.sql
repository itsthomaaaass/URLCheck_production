-- Vertical slice: minimal schema for the monitored_url entity.

CREATE DATABASE IF NOT EXISTS url_monitor
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE url_monitor;

CREATE TABLE IF NOT EXISTS monitored_url (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100)    NOT NULL,
    url        VARCHAR(2048)   NOT NULL,
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
