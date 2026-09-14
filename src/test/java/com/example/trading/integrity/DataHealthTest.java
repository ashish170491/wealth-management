package com.example.trading.integrity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the data-health rule table (SPEC 44).
 *
 * <p>Three properties matter more than the rest.
 *
 * <p><b>The calendar.</b> Screening scores dated Saturday are correct on a Sunday, and still
 * correct on Monday morning. I read that strip by hand during the audit that led to this
 * screen and concluded a job had been missed; it had not. If a reader cannot be trusted to
 * hold the schedule in their head - and they cannot, that is the point of the screen - the
 * calculation has to be encoded and pinned.
 *
 * <p><b>The holiday ambiguity.</b> There is no market-holiday calendar in this JVM, so one
 * missing session and a closed exchange are literally the same observation. Anything that
 * reported the first as a failure would cry wolf several times a year, and the next real
 * alarm would be ignored.
 *
 * <p><b>The expiry on a known cause.</b> A zero that is excused because a bug explains it
 * must stop being excused once that bug's fix has shipped. Without this the exception list
 * becomes the place the next regression hides.
 */
class DataHealthTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // 2026-09-06 is a Sunday. Every date below is anchored to that week.
    private static final LocalDate SAT = LocalDate.of(2026, 9, 5);
    private static final LocalDate SUN = LocalDate.of(2026, 9, 6);
    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate TUE = LocalDate.of(2026, 9, 8);
    private static final LocalDate WED = LocalDate.of(2026, 9, 9);

    private static ZonedDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(IST);
    }

    private static DataHealth.ScheduleSpec spec(String key) {
        return DataHealth.SCHEDULE.get(key);
    }

    /**
     * A schedule pinned to an explicit time, for tests about the RULES rather than about any
     * real job. The three tests below originally read {@code spec("holdingsAnalyzed")} and were
     * calibrated to the 10:30 in its annotation; when that was corrected to the configured 15:15
     * they failed, having been testing a fixture rather than a rule (B-087).
     */
    private static DataHealth.ScheduleSpec at1030() {
        return DataHealth.resolve(spec("holdingsAnalyzed"), "0 30 10 * * MON-FRI");
    }

    // ------------------------------------------------------------------ freshness

    @Test
    @DisplayName("On a Sunday, Saturday's screening scores are up to date")
    void saturdayScoresAreCurrentOnSunday() {
        // The mistake this screen exists to stop a reader making. Saturday runs the weekly
        // full screening; Sunday runs nothing at all, so Saturday's date is the right answer.
        DataHealth.Finding f = DataHealth.freshness(
                spec("multibaggerScores"), SAT, at(SUN, 12, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.OK);
        assertThat(f.figure()).contains("up to date");
    }

    @Test
    @DisplayName("On Monday morning, Saturday's scores are still up to date")
    void saturdayScoresAreCurrentOnMondayMorning() {
        // Monday's screening fires at 14:00, so before then Saturday remains the last run due.
        DataHealth.Finding f = DataHealth.freshness(
                spec("multibaggerScores"), SAT, at(MON, 10, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.OK);
    }

    @Test
    @DisplayName("A weekday-only job is not expected to have run on the weekend")
    void weekdayJobIsNotLateOnASunday() {
        DataHealth.Finding f = DataHealth.freshness(
                at1030(), LocalDate.of(2026, 9, 4), at(SUN, 12, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.OK);
    }

    @Test
    @DisplayName("One session behind is a WATCH that names the holiday ambiguity")
    void oneSessionBehindIsNeverAProblem() {
        DataHealth.Finding f = DataHealth.freshness(at1030(), TUE, at(WED, 12, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.detail()).contains("holiday");
    }

    @Test
    @DisplayName("Two sessions behind is a problem")
    void twoSessionsBehindIsAProblem() {
        DataHealth.Finding f = DataHealth.freshness(at1030(), MON, at(WED, 12, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.PROBLEM);
        assertThat(f.headline()).contains("2 sessions");
    }

    @Test
    @DisplayName("A job is not late until its grace period has passed")
    void gracePeriodPreventsAnAlarmOnTheCronMinute() {
        // holdingsAnalyzed fires at 10:30. At 11:30 the run may legitimately still be writing;
        // a Kite-heavy job takes minutes, and a false alarm here is how a real one gets ignored.
        assertThat(DataHealth.freshness(at1030(), TUE, at(WED, 11, 30)).severity())
                .isEqualTo(DataHealth.Severity.OK);
        assertThat(DataHealth.freshness(at1030(), TUE, at(WED, 12, 30)).severity())
                .isEqualTo(DataHealth.Severity.WATCH);
    }

    @Test
    @DisplayName("A table that only writes when there is something due never escalates")
    void sparseTableStaysAWatchHoweverOld() {
        // Outcomes are recorded when a pick reaches an anniversary, and on many days none does.
        // An old row here is normal, which is exactly why it can never be a liveness check.
        DataHealth.Finding f = DataHealth.freshness(
                spec("recommendationOutcomes"), LocalDate.of(2026, 8, 10), at(WED, 16, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.detail()).contains("only written when there is something due");
    }

    @Test
    @DisplayName("An empty table says so rather than reporting an age")
    void emptyTableIsNotStale() {
        DataHealth.Finding f = DataHealth.freshness(at1030(), null, at(WED, 16, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.figure()).isEqualTo("no rows");
    }

    @Test
    @DisplayName("Every freshness key the health endpoint serves has a schedule")
    void everyKeyIsCovered() {
        // If a new table joins the freshness map without an entry here it is silently unchecked,
        // which is the same shape as the bug this whole screen exists to catch.
        assertThat(DataHealth.SCHEDULE.keySet()).contains(
                "holdingsSynced", "holdingsAnalyzed", "holdingsHistory", "holdingClassification",
                "multibaggerScores", "watchlistAnalyzed", "watchlistSnapshot",
                "recommendationOutcomes");
    }

    // ------------------------------------------------- cron resolution (B-087)

    @Test
    @DisplayName("The configured cron wins over the annotation's inline fallback")
    void configuredCronReplacesTheDeclaredTime() {
        // The bug: @Scheduled(cron = "${holdings.scheduler.analysis-cron:0 30 10 * * MON-FRI}")
        // has 10:30 in the source, and application.yml sets 15:15. Reading the source literal
        // made three healthy tables report as a session behind every morning.
        DataHealth.ScheduleSpec resolved =
                DataHealth.resolve(spec("holdingsAnalyzed"), "0 15 15 * * MON-FRI");
        assertThat(resolved.hour()).isEqualTo(15);
        assertThat(resolved.minute()).isEqualTo(15);
        assertThat(resolved.saturday()).isFalse();
        assertThat(resolved.key()).isEqualTo("holdingsAnalyzed");
    }

    @Test
    @DisplayName("Holdings analysis due at 15:15 is not behind at noon on a Monday")
    void theActualRegression() {
        // Monday lunchtime, newest row from Friday's 15:15 run. Nothing is late: today's run
        // has not happened yet. The screen said "1 session behind" for three tables here.
        DataHealth.ScheduleSpec s = DataHealth.resolve(spec("holdingsAnalyzed"),
                "0 15 15 * * MON-FRI");
        DataHealth.Finding f = DataHealth.freshness(s, LocalDate.of(2026, 9, 4), at(MON, 12, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.OK);

        // ...and it IS behind on Tuesday morning, once Monday's run has come and gone.
        assertThat(DataHealth.freshness(s, LocalDate.of(2026, 9, 4), at(TUE, 12, 0)).severity())
                .isEqualTo(DataHealth.Severity.WATCH);
    }

    @Test
    @DisplayName("A cron that fires several times a day resolves to its last fire")
    void multiHourCronTakesTheLastFire() {
        // The watchlist runs 11:00, 13:00 and 15:00. The table is only final after the last one,
        // so treating 11:00 as the deadline would call it late for four hours every afternoon.
        DataHealth.ScheduleSpec s = DataHealth.resolve(spec("watchlistAnalyzed"),
                "0 0 11,13,15 * * MON-FRI");
        assertThat(s.hour()).isEqualTo(15);
        assertThat(s.minute()).isZero();
    }

    @Test
    @DisplayName("A range resolves to its end, and a Saturday cron sets the Saturday flag")
    void rangeAndSaturday() {
        assertThat(DataHealth.resolve(spec("holdingsSynced"), "0 30 9-15 * * MON-FRI").hour())
                .isEqualTo(15);
        assertThat(DataHealth.resolve(spec("holdingsSynced"), "0 0 8 * * SAT").saturday())
                .isTrue();
    }

    @Test
    @DisplayName("An absent or unreadable cron leaves the declared schedule alone")
    void unreadableCronNeverGuesses() {
        // Guessing here would be worse than the bug it replaces: a wrong schedule produces
        // confident false alarms, which is how a channel stops being read.
        DataHealth.ScheduleSpec base = spec("holdingsAnalyzed");
        for (String cron : new String[] {null, "", "   ", "not a cron", "0 * * * * *",
                                         "0 0 */2 * * MON-FRI", "0 15"}) {
            DataHealth.ScheduleSpec r = DataHealth.resolve(base, cron);
            assertThat(r.hour()).isEqualTo(base.hour());
            assertThat(r.minute()).isEqualTo(base.minute());
        }
    }

    @Test
    @DisplayName("Every schedule either names a cron property or is hard-coded on purpose")
    void everySpecDeclaresWhereItsTimeComesFrom() {
        // The four without a property are the four whose crons really are literals in the
        // annotation: the multibagger pair, the outcome scheduler, the 12:15 IPO capture
        // (SPEC 45.6) and, since 2026-09-10, the 15-minute news scan (SPEC 48.2). That last one
        // is a different case worth knowing: its cron IS a literal, but it is also "*/15", which
        // resolve() cannot read a single fire time out of - so the spec names 15:15, the last fire
        // of the day, rather than pretending the resolver could work it out.
        // The fifth, since 2026-09-12, is the 13:20 analyst target pass (SPEC 49.6). Checked
        // against the annotation rather than against CLAUDE.md: AnalystTargetScheduler carries
        // cron = "0 20 13 * * MON-FRI" as a literal with no property behind it.
        // If a sixth appears, check the annotation before adding it here - someone may have copied
        // a time out of a source file again (B-087).
        long hardCoded = DataHealth.SCHEDULE.values().stream()
                .filter(sp -> sp.cronProperty() == null).count();
        assertThat(hardCoded).isEqualTo(5);
    }

    @Test
    @DisplayName("An on-demand table is never late, because no run was ever due")
    void onDemandIsNeverAProblem() {
        DataHealth.OnDemandSpec spec = DataHealth.ON_DEMAND.get("macroEvents");
        assertThat(spec).as("macroEvents must be declared on-demand, not on a schedule").isNotNull();

        // Never run at all.
        DataHealth.Finding never = DataHealth.onDemandFreshness(spec, null, at(WED, 12, 0));
        assertThat(never.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(never.headline()).contains("on demand");

        // Run today.
        DataHealth.Finding fresh = DataHealth.onDemandFreshness(spec, WED, at(WED, 12, 0));
        assertThat(fresh.severity()).isEqualTo(DataHealth.Severity.OK);

        // Not run for a very long time: still only a WATCH. There is no job that could have
        // failed, so calling it a PROBLEM would be a false alarm on the one channel whose entire
        // job is to be believed.
        DataHealth.Finding stale = DataHealth.onDemandFreshness(
                spec, WED.minusMonths(6), at(WED, 12, 0));
        assertThat(stale.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(stale.detail()).contains("cannot be late");
    }

    @Test
    @DisplayName("Every freshness key on a screen has either a schedule or an on-demand spec")
    void everyKeyIsAccountedFor() {
        // Otherwise a key renders on the strip with no rule behind it, and nothing would ever
        // report it as stale however long it sat still.
        assertThat(DataHealth.SCHEDULE.keySet()).doesNotContainAnyElementsOf(DataHealth.ON_DEMAND.keySet());
        assertThat(DataHealth.ON_DEMAND.values()).allSatisfy(sp -> {
            assertThat(sp.label()).isNotBlank();
            assertThat(sp.trigger()).as("a reader told a table is empty must be told how to fill it")
                    .isNotBlank();
        });
    }

    // ------------------------------------------------------------------- coverage

    @Test
    @DisplayName("A signal measured on no stock, with no known cause, is a problem")
    void zeroCoverageIsAProblem() {
        DataHealth.Finding f = DataHealth.coverage(
                "SomeNewSignal", 0, 288, 0, 0.0, null, null, SAT);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.PROBLEM);
        assertThat(f.figure()).isEqualTo("0 of 288 stocks");
    }

    @Test
    @DisplayName("A zero with a recorded cause is KNOWN, not a problem")
    void zeroCoverageWithAKnownCauseIsExcused() {
        DataHealth.Finding f = DataHealth.coverage(
                "MonthlyRsi", 0, 288, 0, 0.0, null, null, SAT);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.KNOWN);
        assertThat(f.headline()).contains("B-060");
    }

    @Test
    @DisplayName("A known cause stops excusing the zero once its fix has shipped")
    void aKnownCauseExpiresWithItsFix() {
        // InsiderPulse was fixed on 2026-09-05 (B-074). A run on that date or earlier predates
        // the fix and is excused; a later run that still reads zero means the fix did not take.
        assertThat(DataHealth.coverage("InsiderPulseScore", 0, 288, 0, 0.0, null, null,
                LocalDate.of(2026, 9, 5)).severity())
                .isEqualTo(DataHealth.Severity.KNOWN);

        DataHealth.Finding later = DataHealth.coverage("InsiderPulseScore", 0, 288, 0, 0.0,
                null, null, LocalDate.of(2026, 9, 12));
        assertThat(later.severity()).isEqualTo(DataHealth.Severity.PROBLEM);
        assertThat(later.detail()).contains("did not take");
    }

    @Test
    @DisplayName("No applicable stock is not 0% coverage")
    void anEmptyDenominatorIsNotAFailure() {
        // Every stock not-applicable means there is no denominator to take a percentage over.
        // That is a finding about the universe, not a gap (Gotcha 68).
        DataHealth.Finding f = DataHealth.coverage("SomeLenderOnlySignal", 0, 288, 288,
                null, null, null, SAT);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.OK);
        assertThat(f.figure()).isEqualTo("0 applicable");
    }

    @Test
    @DisplayName("A collapsed 0-100 score is a problem")
    void collapsedScoreIsAProblem() {
        DataHealth.Finding f = DataHealth.coverage(
                "SomeScore", 288, 288, 0, 100.0, 1.2, Boolean.TRUE, SAT);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.PROBLEM);
    }

    @Test
    @DisplayName("A signal measured everywhere with zero spread is flagged even when nothing else flags it")
    void identicalOnEveryStockIsFlagged() {
        // The live case: CircuitDays, measured on all 288 with standard deviation 0. Nothing
        // else reports it, because the collapse threshold is a 0-100-score threshold and this
        // is a count (Gotcha 88's fourth rule). It still carries no information.
        DataHealth.Finding f = DataHealth.coverage(
                "CircuitDays", 288, 288, 0, 100.0, 0.0, null, SAT);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.headline()).contains("Identical");
    }

    @Test
    @DisplayName("Thin coverage is reported so an accuracy figure is read against it")
    void lowCoverageIsAWatch() {
        DataHealth.Finding f = DataHealth.coverage(
                "TurnaroundVerdictLike", 19, 288, 0, 6.6, 0.8, null, SAT);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.figure()).contains("19 of 288");
    }

    @Test
    @DisplayName("Thin coverage with a recorded cause names it")
    void lowCoverageCitesAKnownCause() {
        // Otherwise a KNOWN_ZERO entry for a signal that is sparse rather than absent could
        // never fire at all, and a rule that cannot fire reads as a check that ran.
        DataHealth.Finding f = DataHealth.coverage(
                "TurnaroundVerdict", 19, 288, 0, 6.6, 0.8, null, SAT);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.headline()).contains("32.6");
        assertThat(f.detail()).contains("four years of annual accounts");
    }

    @Test
    @DisplayName("A widely measured, varying signal passes")
    void healthySignalPasses() {
        DataHealth.Finding f = DataHealth.coverage(
                "Valuation", 280, 288, 0, 97.2, 14.3, Boolean.FALSE, SAT);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.OK);
    }

    // -------------------------------------------------------------------- history

    @Test
    @DisplayName("A shallow universe is a WATCH about this database, not about the companies")
    void shallowHistoryIsAboutTheDatabase() {
        DataHealth.Finding f = DataHealth.historyDepth(366, 20, 1.0);
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.figure()).contains("20 of 366");
        assertThat(f.detail()).contains("never about the company");
        // Read by a non-expert: "Median depth is 1 years" is the kind of seam that makes a
        // reader trust the rest of the page less, and it was visible on the first live render.
        assertThat(f.detail()).contains("Median depth is 1 year.");
        assertThat(DataHealth.historyDepth(366, 20, 3.5).detail()).contains("3.5 years");
    }

    @Test
    @DisplayName("A stalled backfill queue is a problem; a moving one is not")
    void stalledBackfillIsAProblem() {
        // Four weekdays without an attempt while work remains: the batch is not running.
        assertThat(DataHealth.backfillProgress(300, LocalDate.of(2026, 8, 31), at(WED, 16, 0))
                .severity()).isEqualTo(DataHealth.Severity.PROBLEM);
        assertThat(DataHealth.backfillProgress(300, TUE, at(WED, 16, 0)).severity())
                .isEqualTo(DataHealth.Severity.OK);
    }

    @Test
    @DisplayName("An empty queue is finished, not stalled")
    void emptyQueueIsNotStalled() {
        DataHealth.Finding f = DataHealth.backfillProgress(0, LocalDate.of(2026, 1, 1), at(WED, 16, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.OK);
        assertThat(f.headline()).contains("Nothing left");
    }

    @Test
    @DisplayName("A backfill that has never run says so rather than reporting a stall")
    void neverRunIsNotAStall() {
        DataHealth.Finding f = DataHealth.backfillProgress(366, null, at(WED, 16, 0));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.headline()).contains("never run");
    }

    // ---------------------------------------------------------------- insider feed (B-089)

    @Test
    @DisplayName("A feed that has stopped advancing is a problem, not a quiet market")
    void deadInsiderFeedIsAProblem() {
        // The real outage: newest filing 2026-05-01, read on 2026-09-08.
        DataHealth.Finding f = DataHealth.pitFeedFreshness(
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 9, 8));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.PROBLEM);
        assertThat(f.figure()).contains("2026-05-01");
    }

    @Test
    @DisplayName("The escalation would have fired within a month of the outage, not four")
    void escalatesEarly() {
        LocalDate stopped = LocalDate.of(2026, 5, 1);
        assertThat(DataHealth.pitFeedFreshness(stopped, stopped.plusDays(DataHealth.PIT_FEED_PROBLEM_DAYS))
                .severity()).isEqualTo(DataHealth.Severity.PROBLEM);
    }

    @Test
    @DisplayName("A few days of silence is a watch - a holiday cluster is not a broken feed")
    void shortSilenceIsOnlyAWatch() {
        LocalDate today = LocalDate.of(2026, 9, 8);
        assertThat(DataHealth.pitFeedFreshness(today.minusDays(2), today).severity())
                .isEqualTo(DataHealth.Severity.OK);
        assertThat(DataHealth.pitFeedFreshness(
                        today.minusDays(DataHealth.PIT_FEED_WATCH_DAYS), today).severity())
                .isEqualTo(DataHealth.Severity.WATCH);
    }

    @Test
    @DisplayName("Never having captured a filing is an absence, never an all-clear")
    void noFilingsEverIsNotOk() {
        DataHealth.Finding f = DataHealth.pitFeedFreshness(null, LocalDate.of(2026, 9, 8));
        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.figure()).isEqualTo("no rows");
    }
}
