package com.urlcheck.check;

/**
 * Accessibility verdict for a single check.
 *
 * <p>There is no {@code UNKNOWN} value: a check that just ran always has a
 * definitive answer. "Never checked" is a client-side state only, because
 * check results are not stored yet.
 */
public enum CheckStatus {

    /** The target answered with 2xx or 3xx. */
    UP,

    /** The target answered with 4xx/5xx, or could not be reached at all. */
    DOWN
}
