-- 002: introduce users, assign monitored_url rows to a user,
--      and add checks/changes tables for the timeline feature.
-- Run once against the url_monitor database (docker):
--   docker exec -i url-change-monitor-mysql mysql -uroot -proot < 002_users_urls_timeline.sql

USE url_monitor;

-- 1) users ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)     NOT NULL,
    password_hash VARCHAR(255)    NOT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Seed bootstrap owner for existing data. Plaintext placeholder until real
-- authentication is implemented (legacy-owner / 123).
INSERT INTO users (username, password_hash)
VALUES ('legacy-owner', '123')
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash);

SET @owner_id = (SELECT id FROM users WHERE username = 'legacy-owner' LIMIT 1);

-- 2) monitored_url.user_id -----------------------------------------------
-- Add nullable first, backfill, then tighten.
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'url_monitor'
      AND TABLE_NAME   = 'monitored_url'
      AND COLUMN_NAME  = 'user_id');

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE monitored_url ADD COLUMN user_id BIGINT UNSIGNED NULL AFTER id',
    'SELECT ''monitored_url.user_id already exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

UPDATE monitored_url SET user_id = @owner_id WHERE user_id IS NULL;

ALTER TABLE monitored_url MODIFY COLUMN user_id BIGINT UNSIGNED NOT NULL;

SET @fk_exists = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = 'url_monitor'
      AND TABLE_NAME        = 'monitored_url'
      AND CONSTRAINT_NAME   = 'fk_monitored_url_user');

SET @sql = IF(@fk_exists = 0,
    'ALTER TABLE monitored_url ADD CONSTRAINT fk_monitored_url_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE',
    'SELECT ''fk_monitored_url_user already exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'url_monitor'
      AND TABLE_NAME   = 'monitored_url'
      AND INDEX_NAME   = 'idx_monitored_url_user_id');

SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE monitored_url ADD INDEX idx_monitored_url_user_id (user_id, id)',
    'SELECT ''idx_monitored_url_user_id already exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

-- 3) checks: one row per monitoring check --------------------------------
CREATE TABLE IF NOT EXISTS checks (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    url_id           BIGINT UNSIGNED NOT NULL,
    checked_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    http_status      INT             NULL,
    response_time_ms BIGINT          NULL,
    content_hash     CHAR(64)        NULL,
    success          TINYINT(1)      NOT NULL,
    error_type       VARCHAR(64)     NULL,
    final_url        VARCHAR(2048)   NULL,
    PRIMARY KEY (id),
    KEY idx_checks_url_checked (url_id, checked_at),
    CONSTRAINT fk_checks_monitored_url
        FOREIGN KEY (url_id) REFERENCES monitored_url (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 4) changes: timeline events --------------------------------------------
CREATE TABLE IF NOT EXISTS changes (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    url_id      BIGINT UNSIGNED NOT NULL,
    check_id    BIGINT UNSIGNED NULL,
    detected_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    change_type VARCHAR(64)     NOT NULL,
    old_hash    CHAR(64)        NULL,
    new_hash    CHAR(64)        NULL,
    PRIMARY KEY (id),
    KEY idx_changes_url_detected (url_id, detected_at),
    CONSTRAINT fk_changes_monitored_url
        FOREIGN KEY (url_id) REFERENCES monitored_url (id) ON DELETE CASCADE,
    CONSTRAINT fk_changes_check
        FOREIGN KEY (check_id) REFERENCES checks (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;