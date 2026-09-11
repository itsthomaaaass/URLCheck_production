package com.urlcheck.check;

/**
 * Why a check came back {@link CheckStatus#DOWN}. Never set on a check that
 * answered with a success status.
 */
public enum CheckErrorType {

    /** The target answered, but with a 4xx or 5xx status. */
    HTTP_ERROR,

    /** Connecting or reading timed out. */
    TIMEOUT,

    /** The host name could not be resolved. */
    DNS_ERROR,

    /** The TLS handshake or certificate validation failed. */
    SSL_ERROR,

    /** Nothing accepted a connection on that host and port. */
    CONNECTION_REFUSED,

    /** The target resolves to an internal address or uses a non-web port. */
    BLOCKED_TARGET,

    /** The stored URL is not a usable http(s) URI. */
    INVALID_URL,

    /** More redirects than the checker is willing to follow. */
    TOO_MANY_REDIRECTS,

    /** Any other I/O failure. */
    IO_ERROR,

    /** The checking thread was interrupted, so the URL was not checked. */
    INTERRUPTED
}
