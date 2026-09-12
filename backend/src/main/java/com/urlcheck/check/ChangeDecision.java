package com.urlcheck.check;

/**
 * Semantic outcome of one probe, before any storage policy is applied.
 *
 * @param type    event this outcome represents, or null when there is nothing
 *                worth recording
 * @param status  accessibility verdict of the probe
 * @param changed true when the body differs from the stored hash, false when it
 *                matches, null when there was no usable response
 */
public record ChangeDecision(ChangeType type, CheckStatus status, Boolean changed) {
}