# Database Schema Reference

> Keep this file up to date whenever the schema changes.

## Scope

The MySQL instance currently contains the application database `url_monitor`
plus MySQL system databases (`information_schema`, `mysql`,
`performance_schema`, `sys`), which are not documented here.

Schema migrations live in `database/` and are applied in filename order:

- `001_create_monitored_url.sql` - initial vertical slice table.
- `002_users_urls_timeline.sql` - users, URL ownership, checks and timeline.
- `003_change_event_numbering.sql` - per-URL event numbering on `changes`.
- `004_add_url_description.sql` - free-text description on each URL entry.
- `005_remove_legacy_owner.sql` - drops the legacy bootstrap user.

All application tables use `InnoDB`, `utf8mb4`, `utf8mb4_unicode_ci`.

## Relationships

```text
users 1:N monitored_url
monitored_url 1:N checks
monitored_url 1:N changes
checks 1:N changes (changes.check_id is nullable)
```

- A `monitored_url` row is owned by exactly one user and represents that
  user's list entry. Two users saving the same URL produce two independent
  rows with their own history.
- Deleting a user deletes their URLs (`ON DELETE CASCADE`).
- Deleting a URL deletes its checks and changes (`ON DELETE CASCADE`).
- Deleting a check keeps its change events but nulls `changes.check_id`
  (`ON DELETE SET NULL`).

## users

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| id | bigint unsigned | NO | auto | Primary key |
| username | varchar(50) | NO | - | Unique (`uq_users_username`) |
| password_hash | varchar(255) | NO | - | See note below |
| created_at | datetime | NO | CURRENT_TIMESTAMP | |

Indexes:
- `PRIMARY KEY (id)`
- `UNIQUE uq_users_username (username)`

> Note: every `password_hash` must be a `pbkdf2-sha256$...` value produced by
> `UserPasswordHasher.encode`. Authentication rejects any stored value that is
> not in that format, so a row holding a plaintext password can never log in.
> The `legacy-owner` placeholder seeded by `002` is removed by `005`.

## monitored_url

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| id | bigint unsigned | NO | auto | Primary key |
| user_id | bigint unsigned | NO | - | FK to users.id |
| name | varchar(100) | NO | - | User-facing label |
| url | varchar(2048) | NO | - | Must start http:// or https:// |
| description | varchar(1000) | NO | '' | User note; empty string allowed |
| created_at | datetime | NO | CURRENT_TIMESTAMP | |

Indexes:
- `PRIMARY KEY (id)`
- `KEY idx_monitored_url_user_id (user_id, id)` - list URLs per user

Foreign keys:
- `fk_monitored_url_user` - `user_id` -> `users(id)`, `ON DELETE CASCADE`

> Why no `UNIQUE (user_id, url)`: `url` is `varchar(2048)` and would exceed
> MySQL's index length limit under `utf8mb4`. Enforce per-user URL
> uniqueness in application code if desired.

## checks

One row per monitoring check attempt. Answers: "when did the system check?".

> Status: **written to by nothing yet.** The on-demand accessibility check
> (`POST /api/urls/{id}/check`, see `docs/api.md` section 9) probes the URL and
> returns the outcome without persisting it, so this table stays empty. It is
> kept as the intended home for check history once diagnostics or the timeline
> module need per-check rows; the columns below describe that intended shape
> rather than current behaviour.

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| id | bigint unsigned | NO | auto | Primary key |
| url_id | bigint unsigned | NO | - | FK to monitored_url.id |
| checked_at | datetime(6) | NO | CURRENT_TIMESTAMP(6) | When the check ran |
| http_status | int | YES | - | HTTP status code |
| response_time_ms | bigint | YES | - | Response time in ms |
| content_hash | char(64) | YES | - | SHA-256 hex of the body |
| success | tinyint(1) | NO | - | 1 = success, 0 = failure |
| error_type | varchar(64) | YES | - | e.g. TIMEOUT, DNS_ERROR |
| final_url | varchar(2048) | YES | - | URL after redirects |

Indexes:
- `PRIMARY KEY (id)`
- `KEY idx_checks_url_checked (url_id, checked_at)` - latest checks per URL

Foreign keys:
- `fk_checks_monitored_url` - `url_id` -> `monitored_url(id)`,
  `ON DELETE CASCADE`

## changes

Timeline events. Only records actual events: content changed, site
unavailable, site recovered (later also status changed).

| Column | Type | Null | Default | Notes |
| --- | --- | --- | --- | --- |
| id | bigint unsigned | NO | auto | Primary key |
| url_id | bigint unsigned | NO | - | FK to monitored_url.id |
| check_id | bigint unsigned | YES | - | FK to checks.id that caused the event |
| detected_at | datetime(6) | NO | CURRENT_TIMESTAMP(6) | When the event was detected |
| change_type | varchar(64) | NO | - | CONTENT_CHANGED, UNAVAILABLE, RECOVERED, ... |
| old_hash | char(64) | YES | - | Content hash before |
| new_hash | char(64) | YES | - | Content hash after |
| change_no | bigint unsigned | NO | - | Per-URL event ordinal, assigned on insert |

Indexes:
- `PRIMARY KEY (id)`
- `KEY idx_changes_url_detected (url_id, detected_at)` - timeline query
- `UNIQUE uq_changes_url_change_no (url_id, change_no)` - one sequence per user URL entry

Foreign keys:
- `fk_changes_monitored_url` - `url_id` -> `monitored_url(id)`,
  `ON DELETE CASCADE`
- `fk_changes_check` - `check_id` -> `checks(id)`, `ON DELETE SET NULL`

## Change numbering

`change_no` numbers events **per `monitored_url` row** (each row belongs to one
user, so the numbering is implicitly per user + URL). Every event that lands in
`changes` - content changed, unavailable, recovered - increments the counter.

Insert a new event with the next number in the same transaction:

```sql
INSERT INTO changes (url_id, check_id, change_no, detected_at, change_type)
SELECT ?, ?, COALESCE(MAX(change_no), 0) + 1, NOW(6), ?
FROM changes
WHERE url_id = ?;
```

The unique index `(url_id, change_no)` guards against two writers producing the
same number for one URL entry.

## Typical joins

Timeline for one URL:

```sql
SELECT c.change_type, c.detected_at, c.old_hash, c.new_hash,
       k.http_status, k.success
FROM changes c
LEFT JOIN checks k ON k.id = c.check_id
WHERE c.url_id = ?
ORDER BY c.detected_at DESC;
```

URLs for one user:

```sql
SELECT id, name, url, description, created_at
FROM monitored_url
WHERE user_id = ?
ORDER BY id DESC;
```
