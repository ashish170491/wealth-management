package com.example.trading.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins duration statistics and the one-word verdict (SPEC §56.6). */
class JobStatsAndVerdictTest {

    private static JobStats.Sample s(JobOutcome o, Long dur, Long lag) {
        return new JobStats.Sample(o, dur, lag);
    }

    @Test
    @DisplayName("Skipped runs never count toward duration - they are ~2 ms by construction")
    void skipsExcludedFromDuration() {
        JobStats st = JobStats.of(List.of(
                s(JobOutcome.SUCCEEDED, 60_000L, 0L),
                s(JobOutcome.SKIPPED, 2L, 0L),
                s(JobOutcome.SKIPPED, 3L, 0L),
                s(JobOutcome.COMPLETED_WITH_ERRORS, 80_000L, 0L)));
        assertThat(st.runsMeasured()).isEqualTo(2);
        assertThat(st.medianMs()).isEqualTo(60_000L);
        assertThat(st.maxMs()).isEqualTo(80_000L);
    }

    @Test
    @DisplayName("Below five runs there is no 90th percentile - it would just be the slowest run")
    void noP90OnThinHistory() {
        assertThat(JobStats.of(List.of(s(JobOutcome.SUCCEEDED, 1L, null))).p90Ms()).isNull();
        List<JobStats.Sample> five = new ArrayList<>();
        for (long i = 1; i <= 10; i++) five.add(s(JobOutcome.SUCCEEDED, i * 1000, null));
        assertThat(JobStats.of(five).p90Ms()).isEqualTo(9000L);
    }

    @Test
    @DisplayName("Start lag counts skipped runs too - lag is about the scheduler, not the job")
    void lagIncludesSkips() {
        JobStats st = JobStats.of(List.of(s(JobOutcome.SKIPPED, 2L, 540_000L), s(JobOutcome.RUNNING, null, 999_999L)));
        assertThat(st.maxLagMs()).isEqualTo(540_000L);
    }

    @Test
    @DisplayName("No runs is nulls, not zeros")
    void emptyIsNull() {
        JobStats st = JobStats.of(List.of());
        assertThat(st.runsMeasured()).isZero();
        assertThat(st.medianMs()).isNull();
        assertThat(st.maxLagMs()).isNull();
    }

    // ------------------------------------------------------------------ verdict

    private static JobStatusDto.RunView run(JobOutcome o, Long dur, Long lag, String reason, Integer errs, String first) {
        LocalDateTime t = LocalDateTime.of(2026, 10, 12, 14, 0);
        return new JobStatusDto.RunView(1L, "k", "Job", t, t, t, dur, lag, o.name(), reason, errs, 0, first, "sched-1");
    }

    private static JobStatusDto.Counts counts(int runs, int failed, int errs, int interrupted,
                                              int expected, int missed, int overlapped) {
        return new JobStatusDto.Counts(runs, 0, errs, failed, 0, interrupted, 0,
                expected, expected - missed - overlapped, missed, overlapped, 0);
    }

    private static final JobStats NO_STATS = JobStats.of(List.of());

    @Test
    @DisplayName("A latest run that logged errors is a PROBLEM, and the first error is quoted")
    void erroredIsProblem() {
        var last = run(JobOutcome.COMPLETED_WITH_ERRORS, 1000L, 0L, null, 2, "[IpoTrackingService] value too long");
        JobVerdict v = JobVerdict.judge(last, last, counts(1, 0, 1, 0, 1, 0, 0), NO_STATS, 14);
        assertThat(v.verdict()).isEqualTo(JobVerdict.PROBLEM);
        assertThat(v.reasons().get(0)).contains("2 errors").contains("value too long");
    }

    @Test
    @DisplayName("A fire lost while the app was up is a PROBLEM even when the runs that did happen were clean")
    void missedIsProblem() {
        var last = run(JobOutcome.SUCCEEDED, 1000L, 0L, null, 0, null);
        assertThat(JobVerdict.judge(last, last, counts(3, 0, 0, 0, 4, 1, 0), NO_STATS, 14).verdict())
                .isEqualTo(JobVerdict.PROBLEM);
    }

    @Test
    @DisplayName("A failure that has since recovered is WATCH, not PROBLEM - red that never clears stops being read")
    void recoveredIsWatch() {
        var last = run(JobOutcome.SUCCEEDED, 1000L, 0L, null, 0, null);
        JobVerdict v = JobVerdict.judge(last, last, counts(5, 1, 0, 0, 5, 0, 0), NO_STATS, 14);
        assertThat(v.verdict()).isEqualTo(JobVerdict.WATCH);
        assertThat(v.reasons().get(0)).contains("latest real run was clean");
    }

    @Test
    @DisplayName("Never run: NOT_DUE when nothing fell due, NO_HISTORY when every due fire found the app down")
    void neverRan() {
        assertThat(JobVerdict.judge(null, null, counts(0, 0, 0, 0, 0, 0, 0), NO_STATS, 14).verdict())
                .isEqualTo(JobVerdict.NOT_DUE);
        assertThat(JobVerdict.judge(null, null, new JobStatusDto.Counts(0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 3),
                NO_STATS, 14).verdict()).isEqualTo(JobVerdict.NO_HISTORY);
    }

    @Test
    @DisplayName("A config switch that is off is WATCH and named; a window skip is not a concern")
    void configSkipVsWindowSkip() {
        var ok = run(JobOutcome.SUCCEEDED, 1000L, 0L, null, 0, null);
        var cfg = run(JobOutcome.SKIPPED, 2L, 0L, "Switched off in config (macro.ingest.scheduled is false)", 0, null);
        var win = run(JobOutcome.SKIPPED, 2L, 0L, JobOutcome.WINDOW_SKIP_REASON, 0, null);
        JobVerdict a = JobVerdict.judge(cfg, ok, counts(2, 0, 0, 0, 2, 0, 0), NO_STATS, 14);
        assertThat(a.verdict()).isEqualTo(JobVerdict.WATCH);
        assertThat(a.reasons().get(0)).contains("macro.ingest.scheduled");
        assertThat(JobVerdict.judge(win, ok, counts(2, 0, 0, 0, 2, 0, 0), NO_STATS, 14).verdict()).isEqualTo(JobVerdict.OK);
    }

    @Test
    @DisplayName("A start more than two minutes late is WATCH and names the pool starvation (B-014)")
    void lateStart() {
        var last = run(JobOutcome.SUCCEEDED, 1000L, 540_000L, null, 0, null);
        JobVerdict v = JobVerdict.judge(last, last, counts(1, 0, 0, 0, 1, 0, 0), NO_STATS, 14);
        assertThat(v.verdict()).isEqualTo(JobVerdict.WATCH);
        assertThat(v.reasons().get(0)).contains("9 min 0 s").contains("B-014");
    }

    @Test
    @DisplayName("Slow is >3x typical AND over a minute - a 3 s job taking 10 s is not news")
    void slowRun() {
        List<JobStats.Sample> hist = new ArrayList<>();
        for (int i = 0; i < 6; i++) hist.add(s(JobOutcome.SUCCEEDED, 600_000L, 0L));
        JobStats typical = JobStats.of(hist);
        var slow = run(JobOutcome.SUCCEEDED, 2_400_000L, 0L, null, 0, null);
        assertThat(JobVerdict.judge(slow, slow, counts(7, 0, 0, 0, 7, 0, 0), typical, 14).verdict())
                .isEqualTo(JobVerdict.WATCH);

        List<JobStats.Sample> quick = new ArrayList<>();
        for (int i = 0; i < 6; i++) quick.add(s(JobOutcome.SUCCEEDED, 3_000L, 0L));
        var tenSec = run(JobOutcome.SUCCEEDED, 10_000L, 0L, null, 0, null);
        assertThat(JobVerdict.judge(tenSec, tenSec, counts(7, 0, 0, 0, 7, 0, 0), JobStats.of(quick), 14).verdict())
                .isEqualTo(JobVerdict.OK);
    }

    @Test
    @DisplayName("Durations are said in human units")
    void human() {
        assertThat(JobVerdict.human(850)).isEqualTo("850 ms");
        assertThat(JobVerdict.human(95_000)).isEqualTo("1 min 35 s");
        assertThat(JobVerdict.human(3_960_000)).isEqualTo("1 h 6 min");
    }
}
