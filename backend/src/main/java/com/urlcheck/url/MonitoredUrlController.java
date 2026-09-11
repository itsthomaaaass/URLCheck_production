package com.urlcheck.url;

import java.util.List;

import com.urlcheck.web.SessionUser;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD for the current user's monitored URLs. Auth failures and validation
 * errors are mapped by {@link com.urlcheck.web.ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/urls")
public class MonitoredUrlController {

    private final MonitoredUrlService service;

    public MonitoredUrlController(MonitoredUrlService service) {
        this.service = service;
    }

    @GetMapping
    public List<MonitoredUrl> list(HttpSession session) {
        return service.findAllForUser(SessionUser.requireUserId(session));
    }

    @PostMapping
    public ResponseEntity<MonitoredUrl> create(@RequestBody MonitoredUrl monitoredUrl, HttpSession session) {
        MonitoredUrl created = service.create(SessionUser.requireUserId(session), monitoredUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public MonitoredUrl update(@PathVariable Long id, @RequestBody MonitoredUrl monitoredUrl, HttpSession session) {
        return service.update(SessionUser.requireUserId(session), id, monitoredUrl);
    }

    @DeleteMapping("/{id}")
    public MonitoredUrl delete(@PathVariable Long id, HttpSession session) {
        return service.delete(SessionUser.requireUserId(session), id);
    }
}
