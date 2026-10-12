package com.example.trading.jobs;

/**
 * What one scheduled run amounted to (SPEC §56.3).
 *
 * <p>Stored as a plain string column, never through {@code @Enumerated}: Hibernate writes a CHECK
 * constraint listing the enum members that existed on the day it created the table, and
 * {@code ddl-auto=update} never rewrites it, so adding a value here later would make every write
 * of the new value fail (B-135, Gotcha 148).
 *
 * <p>The vocabulary exists because "did it throw?" is nearly useless in this codebase. Almost
 * every scheduled method wraps its work in {@code try { ... } catch (Exception e) { log.error }},
 * so a job that failed completely returns normally. {@link #COMPLETED_WITH_ERRORS} is the outcome
 * that catches that shape, and it is the one this page exists for.
 */
public enum JobOutcome {

    /** Started, not yet finished, in the current process. */
    RUNNING,
    /** Returned normally and logged no ERROR on its own thread. Says nothing about what it produced. */
    SUCCEEDED,
    /** Returned normally, but logged at least one ERROR on its own thread - usually a caught failure. */
    COMPLETED_WITH_ERRORS,
    /** Threw out of the scheduled method. */
    FAILED,
    /** Returned at a guard: outside the market window, a config switch off, or no broker token. */
    SKIPPED,
    /** Still marked running when the process that ran it went away - the work may be half-done. */
    INTERRUPTED;

    /**
     * The reason stored for a run its market-window guard refused. A constant, so the verdict can
     * tell an expected skip (a fire that lands outside the window) from a job switched off in config.
     */
    public static final String WINDOW_SKIP_REASON = "Outside its window - the job's own market-hours guard "
            + "returned before doing any work (SPEC §3.4). Expected for a fire that lands outside 09:15-15:30.";

    /** True for a run that did (or tried to do) its work, as opposed to declining to. */
    public boolean isRealRun() {
        return this == SUCCEEDED || this == COMPLETED_WITH_ERRORS || this == FAILED || this == INTERRUPTED;
    }

    public boolean isBad() {
        return this == COMPLETED_WITH_ERRORS || this == FAILED || this == INTERRUPTED;
    }

    /** Parses a stored value. An unknown string is null, never a guess. */
    public static JobOutcome parse(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The signals a finished run left behind, and what they add up to.
     *
     * <p>Precedence is the whole rule, and pinned by {@code JobOutcomeTest}:
     * <ol>
     *   <li>it threw - FAILED;</li>
     *   <li>it logged an ERROR - COMPLETED_WITH_ERRORS, even when it also called {@code skip()},
     *       because a job that logged an error did something, and the weekly screening's "no
     *       broker token" abort is an error worth seeing, not a quiet skip;</li>
     *   <li>it named a skip reason - SKIPPED with that reason;</li>
     *   <li>its first window check said closed - SKIPPED, "outside the window";</li>
     *   <li>otherwise SUCCEEDED.</li>
     * </ol>
     */
    public record Signals(Throwable thrown, int errorsLogged, String explicitSkipReason,
                          Boolean firstWindowCheckOpen) {

        public JobOutcome outcome() {
            if (thrown != null) return FAILED;
            if (errorsLogged > 0) return COMPLETED_WITH_ERRORS;
            if (explicitSkipReason != null) return SKIPPED;
            if (Boolean.FALSE.equals(firstWindowCheckOpen)) return SKIPPED;
            return SUCCEEDED;
        }

        /** The plain-English reason to store beside the outcome, or null when there is none. */
        public String reason() {
            return switch (outcome()) {
                case FAILED -> truncate(thrown.getClass().getSimpleName()
                        + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage()), 1000);
                case SKIPPED -> explicitSkipReason != null ? explicitSkipReason : WINDOW_SKIP_REASON;
                default -> null;
            };
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
