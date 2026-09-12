package com.urlcheck.check;

/** What one probe means for one URL entry. */
public enum ChangeType {

    /** First event ever for the URL: the baseline hash was recorded. */
    FIRST_CHECK,

    /** A successful probe whose body hash differs from the stored one. */
    CONTENT_CHANGED,

    /** The probe produced no usable response (4xx/5xx or a network error). */
    UNAVAILABLE,

    /** A successful probe after the previous state was DOWN. */
    RECOVERED
}