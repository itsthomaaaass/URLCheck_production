# Database Schema Reference

Structural reference for the `url_monitor` MySQL schema: tables, columns, keys,
indexes, and relationships. It describes shape only - what the application
stores in those tables and when is documented in `docs/timeline.md` (monitoring
behaviour) and `docs/api.md` (HTTP contract).

## Migrations

Schema changes live in `database/` and are applied in filename order:

- `001_create_monitored_url.sql` - creates the `url_monitor` database and the
  initial `monitored_url` table.
- `002_users_urls_timeline.sql` - adds `users`, `monitored_url.user_id`, and the
  `checks` and `changes` tables.
- `003_change_event_numbering.sql` - adds `changes.change_no` and its unique
  index.
- `004_add_url_description.sql` - adds `monitored_url.description`.
- `005_remove_legacy_owner.sql` - removes the legacy bootstrap user.
- `006_timeline_schedule.sql` - adds the monitoring-state columns to
  `monitored_url` and the detail columns to `changes`.

All application tables use `InnoDB` with character set `utf8mb4` and collation
`utf8mb4_unicode_ci`.

## Relationships

```text
users          1:N  monitored_url
monitored_url  1:N  checks
monitored_url  1:N  changes
checks         1:N  changes        (changes.check_id is nullable)
```

| Child | Column | Parent | On delete |
| --- | --- | --- | --- |
| `monitored_url` | `user_id` | `users(id)` | `CASCADE` |
| `checks` | `url_id` | `monitored_url(id)` | `CASCADE` |
| `changes` | `url_id` | `monitored_url(id)` | `CASCADE` |
| `changes` | `check_id` | `checks(id)` | `SET NULL` |

Constraint names: `fk_monitored_url_user`, `fk_checks_monitored_url`,
`fk_changes_monitored_url`, `fk_changes_check`.

## users

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| id | bigint unsigned | NO | auto | Primary key |
| username | varchar(50) | NO | - | |
| password_hash | varchar(255) | NO | - | |
| created_at | datetime | NO | CURRENT_TIMESTAMP | |

Indexes:
- `PRIMARY KEY (id)`
- `UNIQUE uq_users_username (username)`

## monitored_url

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| id | bigint unsigned | NO | auto | Primary key |
| user_id | bigint unsigned | NO | - | FK to users.id |
| name | varchar(100) | NO | - | |
| url | varchar(2048) | NO | - | |
| description | varchar(1000) | NO | '' | |
| content_hash | char(64) | YES | - | |
| last_status | varchar(8) | YES | - | |
| last_http_status | int | YES | - | |
| last_error_type | varchar(64) | YES | - | |
| last_checked_at | datetime(6) | YES | - | |
| next_check_at | datetime(6) | YES | - | |
| change_count | bigint unsigned | NO | 0 | |
| check_interval_seconds | int unsigned | YES | - | |
| created_at | datetime | NO | CURRENT_TIMESTAMP | |

Indexes:
- `PRIMARY KEY (id)`
- `KEY idx_monitored_url_user_id (user_id, id)`
- `KEY idx_monitored_url_next_check (next_check_at)`

Foreign keys:
- `fk_monitored_url_user` - `user_id` -> `users(id)`, `ON DELETE CASCADE`

> There is no `UNIQUE (user_id, url)`: `url` is `varchar(2048)`, which would
> exceed MySQL's index length limit under `utf8mb4`.

## checks

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| id | bigint unsigned | NO | auto | Primary key |
| url_id | bigint unsigned | NO | - | FK to monitored_url.id |
| checked_at | datetime(6) | NO | CURRENT_TIMESTAMP(6) | |
| http_status | int | YES | - | |
| response_time_ms | bigint | YES | - | |
| content_hash | char(64) | YES | - | |
| success | tinyint(1) | NO | - | |
| error_type | varchar(64) | YES | - | |
| final_url | varchar(2048) | YES | - | |

Indexes:
- `PRIMARY KEY (id)`
- `KEY idx_checks_url_checked (url_id, checked_at)`

Foreign keys:
- `fk_checks_monitored_url` - `url_id` -> `monitored_url(id)`,
  `ON DELETE CASCADE`

## changes

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| id | bigint unsigned | NO | auto | Primary key |
| url_id | bigint unsigned | NO | - | FK to monitored_url.id |
| check_id | bigint unsigned | YES | - | FK to checks.id; nullable |
| detected_at | datetime(6) | NO | CURRENT_TIMESTAMP(6) | |
| change_type | varchar(64) | NO | - | |
| old_hash | char(64) | YES | - | |
| new_hash | char(64) | YES | - | |
| http_status | int | YES | - | |
| error_type | varchar(64) | YES | - | |
| response_time_ms | bigint | YES | - | |
| change_no | bigint unsigned | NO | - | |

Indexes:
- `PRIMARY KEY (id)`
- `KEY idx_changes_url_detected (url_id, detected_at)`
- `UNIQUE uq_changes_url_change_no (url_id, change_no)`

Foreign keys:
- `fk_changes_monitored_url` - `url_id` -> `monitored_url(id)`,
  `ON DELETE CASCADE`
- `fk_changes_check` - `check_id` -> `checks(id)`, `ON DELETE SET NULL`
