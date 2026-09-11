package com.urlcheck.web;

import com.urlcheck.user.SessionAttributes;

import jakarta.servlet.http.HttpSession;

/**
 * Resolves the logged-in user from the HTTP session.
 */
public final class SessionUser {

    /**
     * @return the {@code users.id} stored in the session
     * @throws NotLoggedInException when the session holds no user
     */
    public static Long requireUserId(HttpSession session) {
        Long userId = (Long) session.getAttribute(SessionAttributes.USER_ID);
        if (userId == null) {
            throw new NotLoggedInException();
        }
        return userId;
    }

    private SessionUser() {
    }
}
