package com.example.trading.jobs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins how a finished run is classified (SPEC §56.3).
 *
 * <p>The case that matters most is the second one. Nearly every scheduled method in this app
 * catches its own exception and logs it, so a job that failed outright returns normally. If a
 * logged ERROR did not change the outcome, this page would read green on almost every real
 * failure - B-116 and B-135 both ran for days in exactly that shape.
 */
class JobOutcomeTest {

    @AfterEach
    void clear() {
        JobRunContext.end();
    }

    @Test
    @DisplayName("A thrown exception is FAILED, and the reason names it")
    void thrownIsFailed() {
        var s = new JobOutcome.Signals(new IllegalStateException("boom"), 0, null, true);
        assertThat(s.outcome()).isEqualTo(JobOutcome.FAILED);
        assertThat(s.reason()).isEqualTo("IllegalStateException: boom");
    }

    @Test
    @DisplayName("A swallowed failure - returned normally, logged an ERROR - is not a success")
    void loggedErrorIsNotSuccess() {
        var s = new JobOutcome.Signals(null, 1, null, true);
        assertThat(s.outcome()).isEqualTo(JobOutcome.COMPLETED_WITH_ERRORS);
    }

    @Test
    @DisplayName("An ERROR outranks an explicit skip: the weekly screening's no-token abort is an error worth seeing")
    void errorOutranksSkip() {
        var s = new JobOutcome.Signals(null, 1, "no token", true);
        assertThat(s.outcome()).isEqualTo(JobOutcome.COMPLETED_WITH_ERRORS);
    }

    @Test
    @DisplayName("A config switch that is off reads SKIPPED with the setting named, never a 2 ms success (B-154)")
    void explicitSkipKeepsItsReason() {
        var s = new JobOutcome.Signals(null, 0, "Switched off in config (macro.ingest.scheduled is false)", true);
        assertThat(s.outcome()).isEqualTo(JobOutcome.SKIPPED);
        assertThat(s.reason()).contains("macro.ingest.scheduled");
    }

    @Test
    @DisplayName("A refused first window check is a skip, and says it is the expected kind")
    void windowGuardIsExpectedSkip() {
        var s = new JobOutcome.Signals(null, 0, null, false);
        assertThat(s.outcome()).isEqualTo(JobOutcome.SKIPPED);
        assertThat(s.reason()).isEqualTo(JobOutcome.WINDOW_SKIP_REASON);
    }

    @Test
    @DisplayName("No guard verdict and no signal is a success; a WARN alone does not change that")
    void plainSuccess() {
        assertThat(new JobOutcome.Signals(null, 0, null, null).outcome()).isEqualTo(JobOutcome.SUCCEEDED);
        assertThat(new JobOutcome.Signals(null, 0, null, true).reason()).isNull();
    }

    @Test
    @DisplayName("Only the FIRST window check counts - a later deadline check mid-run is not a skip")
    void onlyFirstWindowCheckCounts() {
        JobRunContext.begin(new JobRunContext.ActiveRun(1L, "k"));
        JobRunContext.noteWindowCheck(true);
        JobRunContext.noteWindowCheck(false);   // the IPO capture asks again as a deadline test
        assertThat(JobRunContext.current().firstWindowCheckOpen).isTrue();
    }

    @Test
    @DisplayName("The first skip reason wins")
    void firstSkipWins() {
        JobRunContext.begin(new JobRunContext.ActiveRun(1L, "k"));
        JobRunContext.skip("first");
        JobRunContext.skip("second");
        assertThat(JobRunContext.current().explicitSkipReason).isEqualTo("first");
    }

    @Test
    @DisplayName("Outside a scheduled run both entry points are no-ops - safe from a controller or page load")
    void noOpWithoutARun() {
        assertThatCode(() -> {
            JobRunContext.skip("x");
            JobRunContext.noteWindowCheck(false);
        }).doesNotThrowAnyException();
        assertThat(JobRunContext.current()).isNull();
    }

    @Test
    @DisplayName("An unknown stored outcome parses to null, never a guess")
    void unknownParsesToNull() {
        assertThat(JobOutcome.parse("EXPLODED")).isNull();
        assertThat(JobOutcome.parse(null)).isNull();
        assertThat(JobOutcome.SKIPPED.isRealRun()).isFalse();
        assertThat(JobOutcome.INTERRUPTED.isBad()).isTrue();
    }
}
