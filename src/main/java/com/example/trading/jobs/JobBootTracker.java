package com.example.trading.jobs;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Knows which process this is, and when it was alive (SPEC §56.4).
 *
 * <p>Two jobs. It writes an {@link AppBootEntity} once per process and keeps its
 * {@code lastSeenAt} moving, so a cron fire can be judged "the app was down" rather than "the job
 * missed". And on start it turns every run a <i>previous</i> process left marked RUNNING into
 * INTERRUPTED - which is how a screening cut off by the 15:35 shutdown, or a restart in the middle
 * of the 14:00 run, becomes visible instead of simply never finishing.
 *
 * <p>Every method swallows its own failure: a ledger that could break the jobs it records would be
 * worse than no ledger.
 */
@Component
@Slf4j
public class JobBootTracker {

    private static final long TOUCH_EVERY_MS = 60_000;

    private final String bootId = UUID.randomUUID().toString();
    private final AppBootRepository boots;
    private final JobRunRepository runs;

    private volatile boolean registered;
    private volatile long lastTouchMs;
    private volatile LocalDateTime startedAt;

    public JobBootTracker(AppBootRepository boots, JobRunRepository runs) {
        this.boots = boots;
        this.runs = runs;
    }

    public String bootId() {
        return bootId;
    }

    public LocalDateTime startedAt() {
        return startedAt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ensureRegistered();
    }

    /** Idempotent. Also called from the first job start, in case a job fires before ready. */
    public synchronized void ensureRegistered() {
        if (registered) return;
        LocalDateTime now = LocalDateTime.now(JobCatalog.IST);
        try {
            boots.save(AppBootEntity.builder()
                    .bootId(bootId)
                    .startedAt(now)
                    .lastSeenAt(now)
                    .pid(ProcessHandle.current().pid())
                    .build());
            startedAt = now;
            registered = true;
            lastTouchMs = System.currentTimeMillis();
            sweepInterrupted();
        } catch (Exception e) {
            log.warn("Job ledger: could not record this process start - the jobs page will read every "
                    + "fire of this session as 'app not running': {}", e.getMessage());
        }
    }

    /** Advances lastSeenAt, at most once a minute. */
    public void touch() {
        long nowMs = System.currentTimeMillis();
        if (!registered || nowMs - lastTouchMs < TOUCH_EVERY_MS) return;
        lastTouchMs = nowMs;
        try {
            boots.touch(bootId, LocalDateTime.now(JobCatalog.IST));
        } catch (Exception e) {
            log.debug("Job ledger: lastSeen update failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void onShutdown() {
        if (!registered) return;
        try {
            boots.touch(bootId, LocalDateTime.now(JobCatalog.IST));
        } catch (Exception e) {
            log.debug("Job ledger: shutdown stamp failed: {}", e.getMessage());
        }
    }

    private void sweepInterrupted() {
        int n = 0;
        for (JobRunEntity r : runs.findByOutcome(JobOutcome.RUNNING.name())) {
            if (bootId.equals(r.getBootId())) continue;
            r.setOutcome(JobOutcome.INTERRUPTED.name());
            r.setReason("The app stopped before this run finished - the session-end shutdown, a restart "
                    + "or a crash. Whatever it was writing may be half-done.");
            runs.save(r);
            n++;
        }
        if (n > 0) {
            log.warn("Job ledger: {} run(s) from a previous session never finished - marked INTERRUPTED", n);
        }
    }
}
