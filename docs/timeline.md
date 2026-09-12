# Scheduled Monitoring and the Timeline

How the backend decides that a monitored page changed, and how that history is
kept. Read next to `docs/database.md` (schema) and `docs/api.md` (endpoints).

## The shape of the feature

- Creating a URL and editing its address schedule a check. No other user action
  touches monitoring state.
- A scheduler inside the backend checks each URL on its own cadence, writes the
  URL's current state, and - only when something real happened - appends one
  timeline row.
- `POST /api/urls/{id}/check` is the manual check. It is read-only: it probes
  the URL, reports what that probe means against the stored baseline, and
  writes nothing at all.
- `GET /api/urls/{id}/timeline` reads the history back. Also read-only.

There is exactly one writer - the scheduler - and two readers.
```text
                        UrlChecker.probe()          network, SSRF screen, timeouts,
                        (shared, no storage)        SHA-256 of the body (capped)
                                 |
              +------------------+------------------+
              |                                     |
              v                                     v
      CheckService (manual)                  MonitorRunner (scheduled)
      reads the stored baseline              claims the URL, then probes
      decides, returns, writes nothing       MonitorWriter (one transaction)
              |                                     |
              v                                     v
      POST /api/urls/{id}/check           monitored_url state + changes rows
                                          + prune to the retention limit
```

Both sides share `ChangeDetector.decide(...)`, so the manual answer and the
recorded answer can never drift apart, but only the scheduled side persists it.

## What one scheduled check does

1. **Claim.** `UPDATE monitored_url SET next_check_at = NOW(6) + interval WHERE
   id = ? AND (next_check_at IS NULL OR next_check_at <= NOW(6))`. Only the
   caller that changes one row proceeds, so two backend instances - or the
   poller and a create-time check - never record the same change twice. All time
   arithmetic stays in SQL, so a JVM clock in another time zone cannot shift it.
2. **Probe.** One GET with the same SSRF screening as before, following up to
   five redirects by hand and hashing the body as it streams.
3. **Decide.** `ChangeDetector` turns the stored baseline plus the probe into a
   `ChangeDecision` (what happened) with no knowledge of the database.
4. **Persist.** `MonitorWriter`, in a single transaction: insert the timeline
   row when there is an event, prune that URL's rows, then write the new state
   (`content_hash`, `last_status`, `last_http_status`, `last_error_type`,
   `last_checked_at`, and `change_count` when a row was added).
## The events

| `change_type` | Written when | Anchor? |
| --- | --- | --- |
| `FIRST_CHECK` | The URL had no stored hash and this probe succeeded | yes |
| `CONTENT_CHANGED` | The probe succeeded and its hash differs from the stored one | no |
| `RECOVERED` | The probe succeeded after the stored state was `DOWN` | no |
| `UNAVAILABLE` | The probe produced no usable response (4xx/5xx or a network error) and the stored failure signature differs | no |

- Nothing is written for "no change". That verdict exists only in the live
  response of a check, never as a row.
- `checks` (the per-check table) stays empty on purpose: a row per check is the
  expensive shape. `changes.check_id` is therefore always `NULL`.
- `status` in the API response is derived from `change_type`; it is not stored.

## Detection rules

| Stored state | This probe | Event | `changed` |
| --- | --- | --- | --- |
| no hash | success | `FIRST_CHECK` | `true` |
| hash equal | success | none | `false` |
| hash differs | success | `CONTENT_CHANGED` | `true` |
| any | 4xx/5xx or network error | `UNAVAILABLE` (deduped) | `null` |
| `DOWN` | success | `RECOVERED` | `true` |

- On failure the stored hash is kept, so a later recovery still has something to
  compare against, and `changed` is `null` rather than a misleading `false`.
- A failure is only recorded when it differs from the stored one
  (`http_status` + `error_type`). A page that returns 403 for a month produces
  one row, not one per check.
- A body that cannot be read at all is a failure (`IO_ERROR`), not a silent
  "unchanged".
## Retention: the first check plus the nine newest events

Each URL keeps at most `CHANGE_RETENTION_PER_URL` rows (default 10):

- the **anchor**, `change_no = 1`, the first check of that URL entry - never
  deleted, whatever its type (a URL whose first check failed has an
  `UNAVAILABLE` anchor, which is the honest original state);
- plus the newest nine other rows.

`change_no` keeps counting upwards, so the anchor stays at 1 while the window
slides: a new event evicts the oldest non-anchor row, nothing else. The prune
runs in the same transaction as the insert, so a URL is never observable with 11
rows, and a check that records nothing deletes nothing.

`monitored_url.change_count` counts every event ever, so the API can report
`共 N 条` while storing ten. The invariant to check by hand:

```sql
SELECT url_id, COUNT(*) AS n FROM changes GROUP BY url_id HAVING n > 10;
SELECT url_id, SUM(change_no = 1) AS anchors FROM changes GROUP BY url_id HAVING anchors <> 1;
```

Both must always return no rows.
## Scheduling and configuration

The cadence per URL lives in `monitored_url.next_check_at`; the scheduler only
decides how often the database is asked. A `fixedDelay` tick therefore cannot
overlap itself, and a slow batch delays the next tick instead of piling up.

| Env var | Default | Meaning |
| --- | --- | --- |
| `CHECK_SCHEDULER_ENABLED` | `true` | Set `false` to stop all automatic checks (tests, local dev). |
| `CHECK_SCHEDULER_TICK_SECONDS` | `15` | How often due URLs are looked for. Keep it at or below the smallest interval. |
| `CHECK_SCHEDULER_INITIAL_DELAY_SECONDS` | `20` | Grace period after startup before the first tick. |
| `CHECK_INTERVAL_SECONDS` | `3600` | Per-URL cadence unless the row overrides it. |
| `CHECK_BATCH_SIZE` | `20` | URLs checked per tick, bounding one burst. |
| `CHANGE_RETENTION_PER_URL` | `10` | Rows kept per URL, anchor included. |
| `CHECK_ON_CREATE` | `true` | Check a new (or re-pointed) URL immediately instead of waiting for a tick. |

To poll every minute in one environment and hourly in another, change
`CHECK_INTERVAL_SECONDS` and leave the code alone. A single URL can override the
cadence through `monitored_url.check_interval_seconds` (set it directly in SQL;
no UI exposes it yet).

Checks run on one thread (`checkExecutor`) so a slow target cannot make the
backend open dozens of connections, and `CallerRunsPolicy` applies back pressure
rather than queueing without bound.
## Where the code lives

| Class | Job |
| --- | --- |
| `check/UrlChecker` | The only outbound HTTP. SSRF screen, redirects, timeouts, body hash. |
| `check/ContentHasher` | SHA-256 of a stream, capped, constant memory. |
| `check/ChangeDetector` | Pure rule: baseline + probe -> what it means. Used by both paths. |
| `check/CheckService` | The manual check. Reads, decides, returns; writes nothing. |
| `monitor/CheckProperties` | `app.check.*` bound from the environment. |
| `monitor/MonitorMapper` | Due list, claim, `findCheckState`, state writes. |
| `monitor/MonitorRunner` | Claim -> probe -> decide -> hand to the writer. |
| `monitor/MonitorWriter` | The one writer: event + prune + state, in one transaction. |
| `monitor/ScheduledChecker` | The `@Scheduled` polling tick. |
| `monitor/UrlCheckRequestedListener` | After-commit, off-thread check for a new or edited URL. |
| `timeline/TimelineMapper` | Insert, list, prune. |
| `timeline/TimelineService` | Ownership check, limit clamp, response shape. |
| `timeline/TimelineController` | `GET /api/urls/{id}/timeline`. |

The rule that keeps the split honest: `com.urlcheck.check` must not import
`com.urlcheck.monitor` or `com.urlcheck.timeline`. The manual path only sees
`MonitoredUrlMapper`, which has no write method for monitoring state.

## Deliberate limitations

- **Dynamic pages.** The raw body is hashed, so a page with a timestamp, nonce
  or rotating ad reports `CONTENT_CHANGED` almost every check. Normalisation
  (ignore whitespace, extract a selector, threshold the diff) is a future
  feature, not a hidden one.
- **Capped hashing.** Bodies larger than 2 MiB are hashed on their first 2 MiB,
  so a change past that offset is invisible.
- **Bot-hostile sites.** A 403 is recorded as `UNAVAILABLE`, which means "we
  could not read it", not "the site is down".
- **No push.** The browser is never notified; it learns about events when it
  calls the API.
- **Editing a URL wipes its timeline.** The stored hash and history describe the
  old page, so an edit to a different address resets the state, deletes the
  rows, and schedules a fresh baseline.