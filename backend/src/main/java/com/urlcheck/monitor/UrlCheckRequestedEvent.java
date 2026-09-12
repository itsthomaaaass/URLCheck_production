package com.urlcheck.monitor;

/**
 * Asks for an immediate check of one URL. Published when a URL is created and
 * when the user edits it to a different address; the listener runs it off the
 * request thread so neither call waits for an outbound request to finish.
 */
public record UrlCheckRequestedEvent(Long urlId) {
}