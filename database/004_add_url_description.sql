-- 004: add a user-editable free-text description to monitored_url.
-- Run once against the url_monitor database:
--   docker exec -i url-change-monitor-mysql mysql -uroot -proot < 004_add_url_description.sql

USE url_monitor;

-- description: user note shown on the URL entry. Empty string is allowed
-- (every URL has the field, but it may be blank). VARCHAR keeps the existing
-- schema style; the API will enforce a 1000-character limit.
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'url_monitor'
      AND TABLE_NAME   = 'monitored_url'
      AND COLUMN_NAME  = 'description');

SET @sql = IF(@col_exists = 0,
    'ALTER TABLE monitored_url ADD COLUMN description VARCHAR(1000) NOT NULL DEFAULT '''' AFTER url',
    'SELECT ''monitored_url.description already exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;
