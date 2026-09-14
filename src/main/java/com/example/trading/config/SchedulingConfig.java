package com.example.trading.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
 */
@Configuration
@Slf4j
public class SchedulingConfig {

    /**
     * Bean name {@code taskScheduler} is what Spring's scheduling annotation post-processor looks
     * up by default, so simply defining it replaces the single-threaded default everywhere.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sched-");
        // Let an in-flight job (e.g. tax-lot capture at 15:28) finish during the 15:30–15:35
        // shutdown ramp rather than being killed mid-write.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        log.info("Scheduler pool initialized: {} threads (prefix '{}') — replaces single-threaded default (B-014)",
                4, "sched-");
        return scheduler;
    }
}
