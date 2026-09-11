-- 003: add per-URL change numbering to the changes (timeline) table.
-- Run once against the url_monitor database:
--   docker exec -i url-change-monitor-mysql mysql -uroot -proot < 003_change_event_numbering.sql

USE url_monitor;

-- change_no: ordinal of the event within one user URL entry.
-- Scoped by url_id, and every monitored_url row belongs to exactly one user,
-- so the number is implicitly per (user, url).
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'url_monitor'
      AND TABLE_NAME   = 'changes'
      AND COLUMN_NAME  = 'change_no');

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE changes ADD COLUMN change_no BIGINT UNSIGNED NOT NULL',
    'SELECT ''changes.change_no already exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'url_monitor'
      AND TABLE_NAME   = 'changes'
      AND INDEX_NAME   = 'uq_changes_url_change_no');

SET @sql = IF(@idx_exists = 0,
    'ALTER TABLE changes ADD UNIQUE INDEX uq_changes_url_change_no (url_id, change_no)',
    'SELECT ''uq_changes_url_change_no already exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;