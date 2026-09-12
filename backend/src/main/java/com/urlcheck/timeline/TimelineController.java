package com.urlcheck.timeline;

import com.urlcheck.web.SessionUser;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of one URL's timeline. Nothing here writes: events are
 * appended exclusively by the scheduled checker, so a manual check and this
 * endpoint can never step on each other.
 */
@RestController
@RequestMapping("/api/urls")
public class TimelineController {

    private final TimelineService service;

    public TimelineController(TimelineService service) {
        this.service = service;
    }

    @GetMapping("/{id}/timeline")
    public TimelineView timeline(
            @PathVariable Long id,
            @RequestParam(name = "limit", required = false) Integer limit,
            HttpSession session) {
        return service.findForUser(SessionUser.requireUserId(session), id, limit);
    }
}