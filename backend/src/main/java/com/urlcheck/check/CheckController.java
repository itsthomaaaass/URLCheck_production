package com.urlcheck.check;

import com.urlcheck.web.SessionUser;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * On-demand accessibility checks, mounted next to the URL CRUD endpoints so a
 * check is addressed by the same {@code /api/urls/{id}} identifier.
 *
 * <p>POST rather than GET because the call reaches out to a third-party site:
 * it is not safe to retry or prefetch, and must not be cached.
 */
@RestController
@RequestMapping("/api/urls")
public class CheckController {

    private final CheckService service;

    public CheckController(CheckService service) {
        this.service = service;
    }

    /**
     * Checks one stored URL right now. No request body; nothing is written to
     * the database. An unreachable site is still a {@code 200}, reported as
     * {@code status: DOWN} plus an {@code errorType}.
     */
    @PostMapping("/{id}/check")
    public CheckResult check(@PathVariable Long id, HttpSession session) {
        return service.check(SessionUser.requireUserId(session), id);
    }
}
