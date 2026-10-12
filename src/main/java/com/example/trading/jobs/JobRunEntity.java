package com.example.trading.jobs;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One fire of one {@code @Scheduled} method (SPEC §56.2).
 *
 * <p>Written by {@link JobRunRecorder} - a row at start marked RUNNING, updated when the method
 * returns. The row is inserted at <b>start</b> deliberately: a run cut off by the 15:35 shutdown
 * or a crash then survives as RUNNING, and the next boot turns it into INTERRUPTED. A ledger that
 * only wrote at the end could never show the one failure that matters most in the afternoon
 * window (B-014) - a job that never finished.
 *
 * <p>All times are IST wall-clock, matching every other table here.
 */
@Entity
@Table(name = "job_run", indexes = {
        @Index(name = "idx_job_run_key_started", columnList = "jobKey,startedAt"),
        @Index(name = "idx_job_run_started", columnList = "startedAt"),
        @Index(name = "idx_job_run_outcome", columnList = "outcome")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Fully-qualified {@code Class.method}, e.g. {@code com.example.trading.holdings.HoldingsScheduler.analyzeHoldings}. */
    @Column(nullable = false, length = 200)
    private String jobKey;

    /** Which process ran it - see {@link AppBootEntity}. */
    @Column(nullable = false, length = 40)
    private String bootId;

    /** The cron fire this run answered: the latest fire at or before {@link #startedAt}. Null if the cron was unknown. */
    private LocalDateTime scheduledFor;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;

    /** startedAt - scheduledFor. Large values mean every scheduler thread was busy (B-014). */
    private Long startLagMs;

    /** A {@link JobOutcome} name. A string column on purpose - see {@link JobOutcome}. */
    @Column(nullable = false, length = 32)
    private String outcome;

    /** Why it was skipped, what it threw, or why it was interrupted. Null for a clean success. */
    @Column(columnDefinition = "TEXT")
    private String reason;

    /** ERROR lines logged on the job's own thread during the run. */
    private Integer errorsLogged;

    /** WARN lines logged on the job's own thread during the run. */
    private Integer warningsLogged;

    /** The first ERROR message, truncated. The thing to read first when a run went wrong. */
    @Column(columnDefinition = "TEXT")
    private String firstError;

    @Column(length = 40)
    private String threadName;
}
