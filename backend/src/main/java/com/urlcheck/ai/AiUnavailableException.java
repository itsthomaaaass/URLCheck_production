package com.urlcheck.ai;

/**
 * Thrown when the assistant cannot reach or cannot use the LLM provider.
 *
 * <p>Deliberately separate from the application's own errors: the URL monitor
 * keeps working when the provider does not, so this is reported as "the
 * assistant is unavailable" rather than as a failure of the feature the user
 * actually asked for. Mapped to HTTP 503 by
 * {@link com.urlcheck.ai.controller.AiController}.
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
