package com.urlcheck.web;

/**
 * Thrown when an endpoint requires a logged-in user but the session has none.
 * Mapped to HTTP 401 by {@link ApiExceptionHandler}.
 */
public class NotLoggedInException extends RuntimeException {

    public NotLoggedInException() {
        super("请先登录");
    }
}
