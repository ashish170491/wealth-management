package com.example.trading.jobs;

/**
 * The run in progress on the current scheduler thread (SPEC §56).
 *
 * <p>Thread-local because Spring runs a scheduled method start to finish on one {@code sched-}
 * thread, and every signal collected here - the error log lines, the guard's verdict, an explicit
 * skip - is emitted on that same thread. Work a job hands to another thread (a reactive WebClient
 * call, a parallel stream) is NOT attributed, and the page says so rather than implying otherwise.
 *
 * <p>Two static entry points exist for code outside this package, and both are no-ops when no run
 * is active, so they are safe to call from a controller or a page load:
 * <ul>
 *   <li>{@link #noteWindowCheck(boolean)} - called by {@code MarketHoursService}. Only the
 *       <b>first</b> window check of a run counts, because the codebase rule (SPEC §3.4) makes that
 *       check the first statement of every scheduled method. A job that later asks again, as a
 *       deadline test mid-run (the IPO capture does), must not be reclassified as skipped.</li>
 *   <li>{@link #skip(String)} - called by a job that returns early for a reason the window check
 *       cannot see: a config switch that is off, or no broker token. Without it such a return
 *       reads as a two-millisecond success, which is how the macro news reader sat switched off
 *       for a month without anyone noticing (B-154).</li>
 * </ul>
 */
public final class JobRunContext {

    private static final ThreadLocal<ActiveRun> CURRENT = new ThreadLocal<>();

    private JobRunContext() {
    }

    /** Mutable per-run state. Touched only by the thread running the job. */
    static final class ActiveRun {
        final Long runId;
        final String jobKey;
        Boolean firstWindowCheckOpen;
        String explicitSkipReason;
        int errorsLogged;
        int warningsLogged;
        String firstError;
        Throwable thrown;

        ActiveRun(Long runId, String jobKey) {
            this.runId = runId;
            this.jobKey = jobKey;
        }
    }

    /**
     * Marks the current run as skipped, naming why. A no-op outside a scheduled run.
     * The first reason wins: a job that skips for one reason does not then skip for another.
     */
    public static void skip(String reason) {
        ActiveRun run = CURRENT.get();
        if (run != null && run.explicitSkipReason == null) {
            run.explicitSkipReason = reason == null || reason.isBlank() ? "skipped by the job" : reason;
        }
    }

    /** Records the market-window guard's answer. Only the first check of a run is kept. */
    public static void noteWindowCheck(boolean open) {
        ActiveRun run = CURRENT.get();
        if (run != null && run.firstWindowCheckOpen == null) {
            run.firstWindowCheckOpen = open;
        }
    }

    static ActiveRun current() {
        return CURRENT.get();
    }

    static void begin(ActiveRun run) {
        CURRENT.set(run);
    }

    static void end() {
        CURRENT.remove();
    }
}
