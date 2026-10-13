package com.example.trading.jobs;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;

/**
 * Whether the jobs page may offer to re-run a job, and whether it may run right now (SPEC §56.9).
 * Pure: no clock, no repository, no broker. {@code JobRerunPolicyTest} pins every rule.
 *
 * <p>SPEC §56.7 used to say there was no "run this job now" endpoint, because a generic one would
 * bypass every job's refusal windows (B-049). This is the version that does not: the windows live
 * <b>here</b>, once, and every re-run passes through them. That is also stricter than what was
 * there before - the existing manual triggers carried windows inconsistently, and the 12-minute
 * {@code POST /api/multibagger/screen} carried none at all.
 *
 * <p>Two decisions are separate on purpose. <b>Offered</b> answers "should this row have a button":
 * only when the job's latest real run went wrong <i>today</i>. <b>Allowed now</b> answers "may it
 * start this minute", and a refusal is shown beside the button with its reason rather than
 * discovered by clicking.
 */
public final class JobRerunPolicy {

    private JobRerunPolicy() {
    }

    /** Kite and NSE work is refused from here to the close on a trading day (B-049, B-014). */
    static final LocalTime AFTERNOON_CRUNCH_START = LocalTime.of(14, 0);
    /** NSE work is refused in this window: the 09:45 FII/DII fetch and the 10:00 report share the session. */
    static final LocalTime NSE_WINDOW_START = LocalTime.of(9, 40);
    static final LocalTime NSE_WINDOW_END = LocalTime.of(10, 15);

    /**
     * Jobs that get no button, each with the reason the page prints instead. Keyed by
     * {@code Class.method}. {@code JobRerunCoverageTest} fails if a scheduled method has neither
     * an entry here nor a {@link JobRerun} method.
     */
    public static final Map<String, String> NOT_RERUNNABLE = Map.of(
            "TokenManagementService.dailyTokenRefresh",
            "No button: the login is also retried automatically the moment Kite rejects a call "
                    + "(B-171) and on every app start, and it runs in the background, so a button "
                    + "could only report that it had asked, not whether the login worked.",
            "MultibaggerScheduler.weeklyFullScreening",
            "No button: this runs only inside the Saturday window (SPEC §3.4, Gotcha 28) and takes "
                    + "over half an hour with the universe sweep. The weekday 14:00 screening "
                    + "refreshes the same scores; inside the Saturday window, POST "
                    + "/api/multibagger/screen re-runs the screening itself.",
            "MultibaggerScheduler.sendWeeklyReport",
            "No button: this runs only inside the Saturday window (SPEC §3.4, Gotcha 28). After a "
                    + "restart it would first run a full, unguarded screening to rebuild its scores.");

    /** What the page draws for one job. Null from {@link #decide} means nothing to offer at all. */
    public record Decision(
            /* True when the row gets a re-run button. */
            boolean offered,
            /* True when that button may be pressed this minute. */
            boolean allowedNow,
            /* Why it is not offered, or why it is refused now. Null when allowed. */
            String reason,
            /* The job sends email, so a re-run may send it twice. */
            boolean sendsEmail,
            /* What the run touches, copied from JobLabels so the confirmation can name it. */
            String touches) {
    }

    /** Everything the decision depends on, gathered by the caller. */
    public record Inputs(
            String shortKey,
            boolean hasAction,
            String touches,
            /* Outcome name of the job's newest real run, or null when it has none. */
            String lastRealOutcome,
            LocalDateTime lastRealStartedAt,
            boolean runningNow,
            /* Label of a re-run already in progress, of any job; null when none. */
            String rerunInProgress,
            LocalDateTime now,
            /* True on a trading day between 09:15 and 15:30 IST. */
            boolean marketOpen,
            /* True when the broker session is live. Only consulted for a job that touches Kite. */
            boolean brokerSessionLive) {
    }

    public static Decision decide(Inputs in) {
        JobOutcome last = JobOutcome.parse(in.lastRealOutcome());
        if (last == null || !last.isBad()) return null;

        boolean email = touchesEmail(in.touches());
        String notOffered = NOT_RERUNNABLE.get(in.shortKey());
        if (notOffered != null) return new Decision(false, false, notOffered, email, in.touches());
        if (!in.hasAction()) {
            return new Decision(false, false, "No re-run is wired for this job.", email, in.touches());
        }

        LocalDate failedOn = in.lastRealStartedAt() == null ? null : in.lastRealStartedAt().toLocalDate();
        if (failedOn == null || !failedOn.equals(in.now().toLocalDate())) {
            return new Decision(false, false, "The run that went wrong was "
                    + (failedOn == null ? "on an unknown day" : "on " + failedOn)
                    + ". A re-run now would do today's work, not that day's, so there is no button; "
                    + "the next scheduled run starts from here.", email, in.touches());
        }

        String refusal = refusalNow(in);
        return new Decision(true, refusal == null, refusal, email, in.touches());
    }

    /** Why a re-run may not start this minute, or null when it may. */
    static String refusalNow(Inputs in) {
        if (in.runningNow()) {
            return "It is running right now.";
        }
        if (in.rerunInProgress() != null) {
            return "Another re-run (" + in.rerunInProgress() + ") is still going. Re-runs go one at "
                    + "a time so two cannot share the broker's rate limit.";
        }
        boolean kite = touchesKite(in.touches());
        boolean nse = touchesNse(in.touches());
        LocalTime t = in.now().toLocalTime();
        if (in.marketOpen() && (kite || nse) && !t.isBefore(AFTERNOON_CRUNCH_START)) {
            return "Refused from 14:00 to the close on a trading day: the screening and the "
                    + "afternoon report jobs need the broker's rate limit and the NSE session, and "
                    + "they stop silently at 15:30 if starved (B-014, B-049). Available after 15:30.";
        }
        if (in.marketOpen() && nse && !t.isBefore(NSE_WINDOW_START) && !t.isAfter(NSE_WINDOW_END)) {
            return "Refused 09:40-10:15 on a trading day: the FII/DII fetch and report share the "
                    + "NSE session. Available from 10:15.";
        }
        if (kite && !in.brokerSessionLive()) {
            return "There is no live broker session yet. The app logs in at startup and again the "
                    + "moment Kite rejects a call; try again in a minute.";
        }
        return null;
    }

    static boolean touchesKite(String touches) {
        return contains(touches, "kite");
    }

    static boolean touchesNse(String touches) {
        return contains(touches, "nse");
    }

    static boolean touchesEmail(String touches) {
        return contains(touches, "email");
    }

    private static boolean contains(String touches, String word) {
        if (touches == null) return false;
        for (String part : touches.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
            if (part.equals(word)) return true;
        }
        return false;
    }
}
