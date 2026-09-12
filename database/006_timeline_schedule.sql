-- 006: scheduled monitoring + timeline.
--
-- Adds the per-URL monitoring state the scheduled checker maintains, and the
-- extra columns one timeline event needs to describe itself. Run once against
-- the url_monitor database:
--   docker exec -i url-change-monitor-mysql mysql -uroot -proot < 006_timeline_schedule.sql
--
-- Every statement is guarded, so re-running the script is harmless.

USE url_monitor;

DROP PROCEDURE IF EXISTS add_col_if_missing;

DELIMITER $$
CREATE PROCEDURE add_col_if_missing(
    IN p_table VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME   = p_table
                     AND COLUMN_NAME  = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 1) monitored_url: the current state of one URL entry --------------------
-- Maintained by the scheduler (MonitorWriter) only. The one exception is a
-- user edit of the URL string, which clears the state and the timeline
-- (MonitoredUrlService) because the old history belongs to the old page.

CALL add_col_if_missing('monitored_url', 'content_hash',
    'CHAR(64) NULL AFTER description');
CALL add_col_if_missing('monitored_url', 'last_status',
    'VARCHAR(8) NULL AFTER content_hash');
CALL add_col_if_missing('monitored_url', 'last_http_status',
    'INT NULL AFTER last_status');
CALL add_col_if_missing('monitored_url', 'last_error_type',
    'VARCHAR(64) NULL AFTER last_http_status');
CALL add_col_if_missing('monitored_url', 'last_checked_at',
    'DATETIME(6) NULL AFTER last_error_type');
CALL add_col_if_missing('monitored_url', 'next_check_at',
    'DATETIME(6) NULL AFTER last_checked_at');
CALL add_col_if_missing('monitored_url', 'change_count',
    'BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER next_check_at');
CALL add_col_if_missing('monitored_url', 'check_interval_seconds',
    'INT UNSIGNED NULL AFTER change_count');

-- The scheduler finds due rows through this index. A NULL next_check_at means
-- "due now", which is how a freshly created row gets its first check.
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'url_monitor'
      AND TABLE_NAME   = 'monitored_url'
      AND INDEX_NAME   = 'idx_monitored_url_next_check');

SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE monitored_url ADD INDEX idx_monitored_url_next_check (next_check_at)',
    'SELECT ''idx_monitored_url_next_check already exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;
-- 2) changes: the detail a timeline event carries --------------------------
-- check_id and content_hash stay untouched: one row per real event only, and
-- the number of rows per URL is capped (see docs/database.md).

CALL add_col_if_missing('changes', 'http_status',
    'INT NULL AFTER new_hash');
CALL add_col_if_missing('changes', 'error_type',
    'VARCHAR(64) NULL AFTER http_status');
CALL add_col_if_missing('changes', 'response_time_ms',
    'BIGINT NULL AFTER error_type');

DROP PROCEDURE IF EXISTS add_col_if_missing;