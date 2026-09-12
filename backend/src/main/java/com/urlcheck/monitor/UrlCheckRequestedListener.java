package com.urlcheck.monitor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Takes the first check of a new URL, and of a URL whose address was edited,
 * off the request thread: the user gets their response without waiting for an
 * outbound request, and the check still happens immediately.
 *
 * <p>It runs after the creating transaction commits, so it reads the committed
 * row, and it is absent when {@code CHECK_ON_CREATE=false}: the URL's
 * next_check_at of NOW(6) then has the poller do the same thing a tick later.
 */
@Component
@ConditionalOnProperty(prefix = "app.check", name = "on-create", havingValue = "true",
        matchIfMissing = true)
public class UrlCheckRequestedListener {

    private final MonitorRunner runner;

    public UrlCheckRequestedListener(MonitorRunner runner) {
        this.runner = runner;
    }

    @Async("checkExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCheckRequested(UrlCheckRequestedEvent event) {
        runner.runIfDue(event.urlId());
    }
}