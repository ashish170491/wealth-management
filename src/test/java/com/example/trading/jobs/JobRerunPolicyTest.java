package com.example.trading.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC §56.9: when the jobs page offers a re-run, and when it refuses one. */
class JobRerunPolicyTest {

    private static final LocalDateTime TODAY_1515 = LocalDateTime.of(2026, 10, 13, 15, 15);
    private static final LocalDateTime TODAY_1540 = LocalDateTime.of(2026, 10, 13, 15, 40);

    private static JobRerunPolicy.Inputs in(String key, String touches, String outcome, LocalDateTime failedAt,
                                            LocalDateTime now, boolean marketOpen) {
        return new JobRerunPolicy.Inputs(key, true, touches, outcome, failedAt, false, null, now, marketOpen, true);
    }

    @Test
    @DisplayName("13 Oct: the 15:15 holdings analysis failed, and at 15:40 it may be re-run")
    void todaysFailureAfterTheClose() {
        JobRerunPolicy.Decision d = JobRerunPolicy.decide(in("HoldingsScheduler.analyzeHoldings",
                "Kite, NSE", "FAILED", TODAY_1515, TODAY_1540, false));
        assertThat(d.offered()).isTrue();
        assertThat(d.allowedNow()).isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    @DisplayName("Finished-with-errors and never-finished are failures too; success and skip offer nothing")
    void whichOutcomesOffer() {
        for (String bad : new String[]{"FAILED", "COMPLETED_WITH_ERRORS", "INTERRUPTED"}) {
            assertThat(JobRerunPolicy.decide(in("X.y", "DB", bad, TODAY_1515, TODAY_1540, false)).offered())
                    .as(bad).isTrue();
        }
        for (String fine : new String[]{"SUCCEEDED", "SKIPPED", "RUNNING", null, "SOMETHING_NEW"}) {
            assertThat(JobRerunPolicy.decide(in("X.y", "DB", fine, TODAY_1515, TODAY_1540, false))).as(fine).isNull();
        }
    }

    @Test
    @DisplayName("A failure on an earlier day gets no button: a re-run would do today's work, not that day's")
    void olderFailureIsNotOffered() {
        JobRerunPolicy.Decision d = JobRerunPolicy.decide(in("TaxLotAutoCaptureScheduler.captureTodayTrades",
                "Kite", "FAILED", TODAY_1515.minusDays(1), TODAY_1540, false));
        assertThat(d.offered()).isFalse();
        assertThat(d.reason()).contains("2026-10-12").contains("today's work");
    }

    @Test
    @DisplayName("Kite or NSE work is refused from 14:00 to the close on a trading day, and offered after it")
    void afternoonCrunch() {
        LocalDateTime t1500 = TODAY_1515.withMinute(0);
        JobRerunPolicy.Decision kite = JobRerunPolicy.decide(in("A.b", "Kite", "FAILED", TODAY_1515.minusHours(2), t1500, true));
        assertThat(kite.offered()).isTrue();
        assertThat(kite.allowedNow()).isFalse();
        assertThat(kite.reason()).contains("14:00").contains("after 15:30");

        JobRerunPolicy.Decision nse = JobRerunPolicy.decide(in("A.b", "NSE", "FAILED", TODAY_1515.minusHours(2), t1500, true));
        assertThat(nse.allowedNow()).isFalse();

        // Same clock time on a non-trading day (market closed): allowed. The window is about
        // contention with the afternoon jobs, and there are none when the market is shut.
        assertThat(JobRerunPolicy.decide(in("A.b", "Kite", "FAILED", TODAY_1515.minusHours(2), t1500, false))
                .allowedNow()).isTrue();
    }

    @Test
    @DisplayName("Email or DB-only work is not refused in the afternoon - it competes for nothing the windows protect")
    void lightJobsAreNotWindowed() {
        JobRerunPolicy.Decision d = JobRerunPolicy.decide(in("A.b", "DB, email", "FAILED",
                TODAY_1515.minusHours(1), TODAY_1515, true));
        assertThat(d.allowedNow()).isTrue();
        assertThat(d.sendsEmail()).isTrue();
    }

    @Test
    @DisplayName("NSE work is refused 09:40-10:15 on a trading day; Kite-only work is not")
    void morningNseWindow() {
        LocalDateTime t0950 = LocalDateTime.of(2026, 10, 13, 9, 50);
        LocalDateTime failed = t0950.minusMinutes(20);
        assertThat(JobRerunPolicy.decide(in("A.b", "NSE", "FAILED", failed, t0950, true)).reason())
                .contains("09:40-10:15");
        assertThat(JobRerunPolicy.decide(in("A.b", "Kite", "FAILED", failed, t0950, true)).allowedNow()).isTrue();
        assertThat(JobRerunPolicy.decide(in("A.b", "NSE", "FAILED", failed, t0950.withHour(10).withMinute(16), true))
                .allowedNow()).isTrue();
    }

    @Test
    @DisplayName("Kite work needs a live broker session; work without Kite does not")
    void brokerSession() {
        JobRerunPolicy.Inputs kite = new JobRerunPolicy.Inputs("A.b", true, "Kite", "FAILED", TODAY_1515,
                false, null, TODAY_1540, false, false);
        assertThat(JobRerunPolicy.decide(kite).reason()).contains("broker session");
        JobRerunPolicy.Inputs amfi = new JobRerunPolicy.Inputs("A.b", true, "AMFI", "FAILED", TODAY_1515,
                false, null, TODAY_1540, false, false);
        assertThat(JobRerunPolicy.decide(amfi).allowedNow()).isTrue();
    }

    @Test
    @DisplayName("One re-run at a time, and never while the job itself is running")
    void oneAtATime() {
        JobRerunPolicy.Inputs other = new JobRerunPolicy.Inputs("A.b", true, "DB", "FAILED", TODAY_1515,
                false, "Holdings report emails", TODAY_1540, false, true);
        assertThat(JobRerunPolicy.decide(other).reason()).contains("Holdings report emails");
        JobRerunPolicy.Inputs self = new JobRerunPolicy.Inputs("A.b", true, "DB", "FAILED", TODAY_1515,
                true, null, TODAY_1540, false, true);
        assertThat(JobRerunPolicy.decide(self).reason()).contains("running right now");
    }

    @Test
    @DisplayName("A job listed as not re-runnable explains itself instead of offering a button")
    void excludedJobs() {
        JobRerunPolicy.Decision d = JobRerunPolicy.decide(in("MultibaggerScheduler.weeklyFullScreening",
                "Kite, NSE", "FAILED", TODAY_1515, TODAY_1540, false));
        assertThat(d.offered()).isFalse();
        assertThat(d.reason()).contains("Saturday");
    }

    @Test
    @DisplayName("'touches' is matched on whole words: 'Broker research feed, Kite' is Kite; 'NSEX' would not be NSE")
    void wordMatching() {
        assertThat(JobRerunPolicy.touchesKite("Broker research feed, Kite")).isTrue();
        assertThat(JobRerunPolicy.touchesNse("NSEX")).isFalse();
        assertThat(JobRerunPolicy.touchesEmail("Kite, NSE, email")).isTrue();
        assertThat(JobRerunPolicy.touchesKite(null)).isFalse();
    }

    @Test
    @DisplayName("A manual run ignores window checks, so a mid-run deadline test after the close cannot file it as SKIPPED")
    void manualRunIgnoresWindowCheck() {
        JobRunContext.begin(new JobRunContext.ActiveRun(1L, "k", true));
        try {
            JobRunContext.noteWindowCheck(false);
            assertThat(JobRunContext.current().firstWindowCheckOpen).isNull();
        } finally {
            JobRunContext.end();
        }
        JobRunContext.begin(new JobRunContext.ActiveRun(2L, "k"));
        try {
            JobRunContext.noteWindowCheck(false);
            assertThat(JobRunContext.current().firstWindowCheckOpen).isFalse();
        } finally {
            JobRunContext.end();
        }
    }
}
