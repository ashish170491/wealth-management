package com.example.trading.jobs;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** Wire shapes for {@code /api/jobs} (SPEC §56.7). Every field that can be unknown is a wrapper. */
public final class JobStatusDto {

    private JobStatusDto() {
    }

    public record Overview(
            LocalDateTime generatedAt,
            int windowDays,
            /* When this ledger's history begins - nothing before it was ever recorded. */
            LocalDateTime historyStartsAt,
            LocalDateTime appStartedAt,
            int poolSize,
            /* Scheduler threads busy right now; null when the pool cannot be read. */
            Integer threadsBusy,
            Summary summary,
            List<JobRow> jobs,
            List<RunView> runningNow,
            List<Problem> problems,
            List<String> notRecorded) {
    }

    public record Summary(int jobs, int problem, int watch, int ok, int noHistory,
                          int failedRuns, int errorRuns, int interruptedRuns,
                          int missedFires, int overlappedFires) {
    }

    public record JobRow(
            String key,
            String shortKey,
            String label,
            /* What the run touches - Kite, NSE, email - so broker contention is visible. Null when unlabelled. */
            String touches,
            String cron,
            String schedule,
            List<LocalTime> timesOnRunDay,
            int outsideWindowOnRunDay,
            int runsPerYear,
            LocalDateTime nextRun,
            RunView lastRun,
            /* The newest run that did its work rather than returning at a guard. */
            RunView lastRealRun,
            LocalDateTime lastSucceededAt,
            JobStats stats,
            Counts counts,
            /* PROBLEM / WATCH / OK / NO_HISTORY / NOT_DUE. */
            String verdict,
            List<String> reasons) {
    }

    public record Counts(int runs, int succeeded, int withErrors, int failed, int skipped,
                         int interrupted, int running,
                         int firesExpected, int ran, int missed, int overlapped, int appNotRunning) {
    }

    public record RunView(
            Long id,
            String jobKey,
            String label,
            LocalDateTime scheduledFor,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Long durationMs,
            Long startLagMs,
            String outcome,
            String reason,
            Integer errorsLogged,
            Integer warningsLogged,
            String firstError,
            String threadName) {
    }

    /** One thing that went wrong: a bad run, or a fire that should have run and did not. */
    public record Problem(LocalDateTime at, String jobKey, String label, String kind,
                          String headline, String detail) {
    }

    public record History(String jobKey, String label, String schedule, int windowDays,
                          JobStats stats, List<RunView> runs) {
    }

    public record Timeline(LocalDate date, LocalDate previousDate, LocalDate nextDate,
                           int poolSize, List<RunView> runs) {
    }
}
