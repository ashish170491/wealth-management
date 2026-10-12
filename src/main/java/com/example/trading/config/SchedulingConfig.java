package com.example.trading.config;

import com.example.trading.jobs.JobRunRecorder;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Provides a multi-threaded {@link TaskScheduler} for all {@code @Scheduled} jobs.
 *
 * <p><b>Why this exists (B-014):</b> Spring's default scheduler runs every {@code @Scheduled}
 * method on a <i>single</i> thread ({@code scheduling-1}). The afternoon window (15:00–15:30 IST)
 * is densely packed — 15:00 breakout scan, 15:15 holdings reports, the multibagger screen,
 * 15:22 recommendation-outcome update, 15:25 weekly accuracy email, 15:28 tax-lot capture — and a
 * single long-running scan (multibagger/holdings can run 14+ minutes) serialises behind it, pushing
 * the later jobs past the 15:30 market-close cutoff. Because every job guards on
 * {@code MarketHoursService.isMarketOpen()}, a job pushed past 15:30 then <i>silently self-aborts</i>.
 * On Fri 2026-05-22 this starved the 15:22 recommendation-outcome update and the 15:25 weekly
 * accuracy email entirely, and delayed tax-lot capture to 15:31 where it skipped with
 * "market closed".
 *
 * <p>A small pool lets the lightweight, time-critical jobs (recommendation outcome, tax capture,
 * accuracy email — all quick DB / single-price reads) get a thread even while a heavy Kite-bound
 * scan occupies another. The pool is deliberately kept small (not large) so we never run several
 * Kite-heavy scans in parallel and trip the broker's rate limit (429).
 *
 * <p>Pool sizing: 4 threads. The heavy scans (multibagger, breakout, sector reversal) are staggered
 * across the day and rarely overlap; 4 is comfortably enough to keep the afternoon batch from
 * starving without inviting wide concurrency.
 *
 * <p><b>Job-run ledger (SPEC §56).</b> This class also hands the scheduler an
 * {@link ObservationRegistry} carrying {@link JobRunRecorder}. Spring 6.2 wraps every
 * {@code @Scheduled} method in an observation, but with no registry configured it uses a no-op one
 * and the hook never fires; this app has no Actuator to supply one. Wiring it here records every
 * job, present and future, with no edit to any job.
 */
@Configuration
@Slf4j
public class SchedulingConfig implements SchedulingConfigurer {

    /** Exposed so the jobs page can say "N of 4 threads busy" without restating the number. */
    public static final int POOL_SIZE = 4;

    private final ObjectProvider<JobRunRecorder> recorder;

    public SchedulingConfig(ObjectProvider<JobRunRecorder> recorder) {
        this.recorder = recorder;
    }

    /**
     * Called once, after every bean exists and before the first job is scheduled - so fetching
     * the recorder lazily here cannot create a start-up cycle with the JPA repositories it needs.
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        JobRunRecorder handler = recorder.getIfAvailable();
        if (handler == null) {
            log.warn("Job-run ledger not wired: no JobRunRecorder bean - the jobs page will show no runs");
            return;
        }
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        registrar.setObservationRegistry(registry);
        log.info("Job-run ledger wired: every @Scheduled run is recorded to job_run (SPEC §56)");
    }

    /**
     * Bean name {@code taskScheduler} is what Spring's scheduling annotation post-processor looks
     * up by default, so simply defining it replaces the single-threaded default everywhere.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("sched-");
        // Let an in-flight job (e.g. tax-lot capture at 15:28) finish during the 15:30–15:35
        // shutdown ramp rather than being killed mid-write.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        log.info("Scheduler pool initialized: {} threads (prefix '{}') — replaces single-threaded default (B-014)",
                POOL_SIZE, "sched-");
        return scheduler;
    }
}
