# Change Detection

## Purpose

URLCheck decides whether the content of a monitored URL has changed since the
last time it was checked.

## Process

1. The scheduler claims the URLs that are due.
2. The page is fetched and its body is hashed with SHA-256.
3. The new hash is compared with the one stored on the URL.
4. A difference is recorded as a timeline event.

## Important behaviour

A failed request is not a change: an outage is recorded separately, so a site
that is briefly down does not look like an edit.