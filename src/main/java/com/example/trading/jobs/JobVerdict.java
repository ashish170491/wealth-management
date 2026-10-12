package com.example.trading.jobs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One job's health in a word, with the sentences it was reached on (SPEC §56.6). Pure.
 *
 * <p>PROBLEM is reserved for things that are wrong <i>now</i>: the latest real run failed, logged
 * errors or never finished, or a fire was lost while the app was up. A bad run last Tuesday that
 * has since run cleanly is WATCH, not PROBLEM - a page that stays red after a recovery is a page
 * whose red stops being read (Gotcha 106(b), the same reason Data Health never escalates a single
 * missed session).
 *
 * <p>Every verdict carries its reasons. A badge with no sentence beside it asks to be trusted.
 */
public record JobVerdict(String verdict, List<String> reasons) {

    public static final String PROBLEM = "PROBLEM";
    public static final String WATCH = "WATCH";
    public static final String OK = "OK";
    /** Due at least once since the ledger began, but every due fire fell while the app was down. */
    public static final String NO_HISTORY = "NO_HISTORY";
    /** Not due at all since the ledger began - a weekly or quarterly job on a young ledger. */
    public static final String NOT_DUE = "NOT_DUE";

    static final long LATE_START_MS = 120_000;
    static final int SLOW_FACTOR = 3;
    static final long SLOW_FLOOR_MS = 60_000;

    public static JobVerdict judge(JobStatusDto.RunView lastRun, JobStatusDto.RunView lastRealRun,
                                   JobStatusDto.Counts c, JobStats stats, int windowDays) {
        List<String> problems = new ArrayList<>();
        List<String> watch = new ArrayList<>();

        JobOutcome last = lastRealRun == null ? null : JobOutcome.parse(lastRealRun.outcome());
        if (last == JobOutcome.FAILED) {
            problems.add("The last run threw: " + nz(lastRealRun.reason()));
        } else if (last == JobOutcome.COMPLETED_WITH_ERRORS) {
            problems.add("The last run finished but logged " + plural(lastRealRun.errorsLogged(), "error")
                    + ". First: " + nz(lastRealRun.firstError()));
        } else if (last == JobOutcome.INTERRUPTED) {
            problems.add("The last run never finished - the app stopped while it was running, so its work may be half-done.");
        }
        if (c.missed() > 0) {
            problems.add(plural(c.missed(), "fire") + " in the last " + windowDays
                    + " days did not run although the app was up and no earlier run of this job was still going.");
        }
        if (!problems.isEmpty()) return new JobVerdict(PROBLEM, problems);

        boolean anyRun = c.runs() > 0 || lastRun != null;
        if (!anyRun) {
            if (c.firesExpected() == 0) {
                return new JobVerdict(NOT_DUE, List.of("Not due yet since this ledger began recording."));
            }
            return new JobVerdict(NO_HISTORY, List.of(plural(c.firesExpected(), "fire")
                    + " fell due in the window, every one while the app was not running."));
        }

        int bad = c.failed() + c.withErrors() + c.interrupted();
        if (bad > 0) {
            watch.add(plural(bad, "bad run") + " in the last " + windowDays + " days; the latest real run was clean.");
        }
        if (c.overlapped() > 0) {
            watch.add(plural(c.overlapped(), "fire") + " were swallowed because the previous run was still "
                    + "going - this job sometimes runs longer than its own interval.");
        }
        if (lastRealRun != null && lastRealRun.startLagMs() != null && lastRealRun.startLagMs() > LATE_START_MS) {
            watch.add("The last run started " + human(lastRealRun.startLagMs())
                    + " after its scheduled time - every scheduler thread was busy (B-014).");
        }
        if (lastRealRun != null && lastRealRun.durationMs() != null && stats != null
                && stats.runsMeasured() >= JobStats.MIN_FOR_P90 && stats.medianMs() != null
                && lastRealRun.durationMs() > SLOW_FLOOR_MS
                && lastRealRun.durationMs() > SLOW_FACTOR * stats.medianMs()) {
            watch.add("The last run took " + human(lastRealRun.durationMs()) + ", against a typical "
                    + human(stats.medianMs()) + ".");
        }
        if (lastRun != null && JobOutcome.SKIPPED.name().equals(lastRun.outcome())
                && lastRun.reason() != null && !JobOutcome.WINDOW_SKIP_REASON.equals(lastRun.reason())) {
            watch.add("The last fire was skipped by the job itself: " + lastRun.reason());
        }
        if (!watch.isEmpty()) return new JobVerdict(WATCH, watch);

        if (lastRealRun == null) {
            return new JobVerdict(OK, List.of("Every recorded fire fell outside the market window and was "
                    + "refused by the guard, as designed. No real run yet."));
        }
        return new JobVerdict(OK, List.of("The last real run succeeded and no fire was lost."));
    }

    static String plural(Integer n, String noun) {
        int v = n == null ? 0 : n;
        return v + " " + noun + (v == 1 ? "" : "s");
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "(no message recorded)" : s;
    }

    /** 1234 -> "1.2 s", 95000 -> "1 min 35 s", 4000000 -> "1 h 6 min". */
    static String human(long ms) {
        if (ms < 1000) return ms + " ms";
        Duration d = Duration.ofMillis(ms);
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) return h + " h " + m + " min";
        if (m > 0) return m + " min " + s + " s";
        return String.format("%.1f s", ms / 1000.0);
    }
}
