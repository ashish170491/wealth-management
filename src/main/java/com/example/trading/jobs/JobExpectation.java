package com.example.trading.jobs;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Every fire a job's cron called for, set against what actually happened (SPEC §56.4). Pure.
 *
 * <p>The run ledger alone cannot answer "did it run when it should have?" - a fire that never ran
 * leaves no row at all. This class walks the cron instead, and gives each fire one of four
 * answers. The distinction that matters, and the reason {@link AppBootEntity} exists:
 *
 * <ul>
 *   <li>{@link Status#APP_NOT_RUNNING} - nobody can blame the job. This app is started at 09:00 and
 *       stopped at ~15:35 by Windows tasks, so most "no row" fires are this, and calling them
 *       missed would put a permanent false alarm on the page (the reason a missed session is never
 *       a PROBLEM on Data Health either, SPEC §44.3 rule 1).</li>
 *   <li>{@link Status#OVERLAPPED} - the app was up but the previous run of this same job was still
 *       going. Spring computes a cron's next fire from when the last run <i>finished</i>, so a run
 *       that overruns its own interval silently swallows the fires it overlaps.</li>
 *   <li>{@link Status#MISSED} - the app was demonstrably up, nothing of this job was running, and
 *       no run row exists. The one genuinely unexplained case.</li>
 * </ul>
 *
 * <p>Fires before the first recorded boot are not returned at all: they predate the ledger, and
 * "we have no record" is not "it did not run".
 */
public final class JobExpectation {

    private JobExpectation() {
    }

    public enum Status { RAN, APP_NOT_RUNNING, OVERLAPPED, MISSED }

    /** A process lifetime: from start to the last moment it was seen alive. */
    public record Boot(LocalDateTime startedAt, LocalDateTime lastSeenAt) {
    }

    /** What the ledger recorded about one run. finishedAt is null for a run still going or cut off. */
    public record RunSpan(LocalDateTime scheduledFor, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record Fire(LocalDateTime at, Status status, boolean outsideWindow) {
    }

    /**
     * @param fires          every cron fire in the window, oldest first (from {@link JobSchedule#firesBetween})
     * @param boots          every boot alive during the window; the current one with lastSeenAt = now
     * @param runs           this job's runs in (and shortly before) the window
     * @param historyStart   the first boot ever recorded; earlier fires are dropped
     * @param grace          how long after a fire a run may start and still count, and how long the
     *                       app must have stayed up after it for the fire to be judged at all
     * @param outsideWindow  per fire, whether the market-window guard refuses it (same order as fires)
     */
    public static List<Fire> classify(List<LocalDateTime> fires, List<Boolean> outsideWindow,
                                      List<Boot> boots, List<RunSpan> runs,
                                      LocalDateTime historyStart, Duration grace) {
        List<Fire> out = new ArrayList<>();
        if (historyStart == null) return out;
        for (int i = 0; i < fires.size(); i++) {
            LocalDateTime fire = fires.get(i);
            if (fire.isBefore(historyStart)) continue;
            boolean outside = outsideWindow != null && i < outsideWindow.size() && Boolean.TRUE.equals(outsideWindow.get(i));
            out.add(new Fire(fire, statusOf(fire, boots, runs, grace), outside));
        }
        return out;
    }

    /**
     * The first instant this job can fairly be judged from (B-184). {@code historyStart} is the first
     * boot of the <i>app</i>, which says nothing about when a given job was added: a job introduced
     * today was "up and not running" on every fire of the last fortnight and read PROBLEM until they
     * aged out. A job exists from its first recorded run (a manual first run counts - it proves the
     * code was there); a job that has never run exists from the boot now serving it, because that
     * process is the only one known to carry it. Never earlier than {@code historyStart}.
     *
     * @param firstRunAt       when this job's earliest retained run started, or null if it has none
     * @param currentBootStart when the running process started, or null if unknown
     */
    public static LocalDateTime judgedFrom(LocalDateTime historyStart, LocalDateTime firstRunAt,
                                           LocalDateTime currentBootStart, Duration grace) {
        if (historyStart == null) return null;
        LocalDateTime candidate = firstRunAt != null ? firstRunAt.minus(grace) : currentBootStart;
        if (candidate == null || candidate.isBefore(historyStart)) return historyStart;
        return candidate;
    }

    static Status statusOf(LocalDateTime fire, List<Boot> boots, List<RunSpan> runs, Duration grace) {
        for (RunSpan r : runs) {
            if (fire.equals(r.scheduledFor())) return Status.RAN;
            if (r.scheduledFor() == null && r.startedAt() != null
                    && !r.startedAt().isBefore(fire) && r.startedAt().isBefore(fire.plus(grace))) {
                return Status.RAN;
            }
        }
        boolean up = false;
        for (Boot b : boots) {
            if (b.startedAt() != null && b.lastSeenAt() != null
                    && !b.startedAt().isAfter(fire) && !b.lastSeenAt().isBefore(fire.plus(grace))) {
                up = true;
                break;
            }
        }
        if (!up) return Status.APP_NOT_RUNNING;
        for (RunSpan r : runs) {
            if (r.startedAt() != null && r.startedAt().isBefore(fire)
                    && (r.finishedAt() == null || r.finishedAt().isAfter(fire))) {
                return Status.OVERLAPPED;
            }
        }
        return Status.MISSED;
    }
}
