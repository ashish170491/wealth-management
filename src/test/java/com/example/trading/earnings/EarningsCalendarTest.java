package com.example.trading.earnings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the next-result estimate (SPEC §50.4) and the fiscal calendar it rests on (§50.2).
 *
 * <p>The properties defended here are about what the estimate refuses to claim: it answers with a
 * window rather than a date because the app does not read board-meeting notices; it measures a
 * company's habit with a median rather than a mean; it never measures that habit from its own
 * assumption; and PAST_DUE says in words that it cannot tell a late company from an uncaptured one.
 */
class EarningsCalendarTest {

    private static QuarterlyResultEntity filed(String quarterEnd, int lagDays) {
        LocalDate d = LocalDate.parse(quarterEnd);
        return QuarterlyResultEntity.builder()
                .symbol("TEST")
                .quarterEnd(d)
                .availableFrom(d.plusDays(lagDays))
                .availableFromEstimated(false)
                .build();
    }

    private static List<QuarterlyResultEntity> series(int... lags) {
        List<QuarterlyResultEntity> rows = new ArrayList<>();
        LocalDate d = LocalDate.parse("2026-06-30");
        for (int lag : lags) {
            rows.add(filed(d.toString(), lag));
            d = d.minusMonths(3).with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        }
        return rows;
    }

    // ------------------------------------------------------------------ the fiscal calendar

    @Test
    @DisplayName("The Indian fiscal year runs April to March")
    void fiscalLabels() {
        assertThat(FiscalQuarter.label(LocalDate.parse("2026-06-30"))).isEqualTo("Q1 FY27");
        assertThat(FiscalQuarter.label(LocalDate.parse("2026-09-30"))).isEqualTo("Q2 FY27");
        assertThat(FiscalQuarter.label(LocalDate.parse("2026-12-31"))).isEqualTo("Q3 FY27");
        assertThat(FiscalQuarter.label(LocalDate.parse("2027-03-31"))).isEqualTo("Q4 FY27");
    }

    @Test
    @DisplayName("The next quarter end snaps to the last day of its month")
    void nextQuarterEndSnaps() {
        // A plain plusMonths(3) from 30-Sep gives 30-Dec, a date no company reports for.
        assertThat(FiscalQuarter.next(LocalDate.parse("2026-09-30")))
                .isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(FiscalQuarter.next(LocalDate.parse("2026-12-31")))
                .isEqualTo(LocalDate.parse("2027-03-31"));
        assertThat(FiscalQuarter.next(LocalDate.parse("2027-03-31")))
                .isEqualTo(LocalDate.parse("2027-06-30"));
    }

    // ------------------------------------------------------------------ the estimate

    @Test
    @DisplayName("The answer is a window, never a single date")
    void answersWithAWindow() {
        var e = EarningsCalendar.next(series(40, 42, 38, 41), LocalDate.parse("2026-08-15"));

        assertThat(e.windowStart()).isNotNull();
        assertThat(e.windowEnd()).isNotNull();
        assertThat(e.windowEnd()).isAfter(e.windowStart());
        assertThat(e.text().toLowerCase(Locale.ROOT)).contains("window rather than a date");
    }

    @Test
    @DisplayName("A company's habit is measured with a median, so one delayed quarter cannot move it")
    void medianNotMean() {
        // Three quarters at ~40 days and one dragged out to 110 by an auditor dispute. A mean
        // would report 57 and push every future window weeks out with it.
        Integer median = EarningsCalendar.medianLag(series(40, 40, 42, 110));

        assertThat(median).isNotNull();
        assertThat(median).isBetween(40, 42);
    }

    @Test
    @DisplayName("An estimated publication date is never measured as a filing habit")
    void estimatedDatesDoNotCountAsEvidence() {
        // An estimated availableFrom is derived from the quarter end, so a lag measured from it
        // would be measuring this app's own assumption and reporting it as the company's habit.
        List<QuarterlyResultEntity> rows = series(45, 45, 45);
        for (QuarterlyResultEntity r : rows) {
            r.setAvailableFromEstimated(true);
        }

        assertThat(EarningsCalendar.medianLag(rows)).isNull();
        assertThat(EarningsCalendar.lagSamples(rows)).isZero();
    }

    @Test
    @DisplayName("A company that reports early gets an early window, not the population default")
    void aMeasuredHabitOutranksTheDefault() {
        // Found on the first live run against Infosys, which files around day 20. Flooring its
        // window at the 25-day population default opened it AFTER the company would already have
        // reported — an estimate contradicted by the record it was built from. The floor belongs
        // to the no-history case only.
        var e = EarningsCalendar.next(series(20, 22, 21, 23), LocalDate.parse("2026-10-20"));

        assertThat(e.typicalLagDays()).isBetween(20, 22);
        assertThat(e.windowStart())
                .as("the window must open before a habitually early filer reports")
                .isBeforeOrEqualTo(LocalDate.parse("2026-09-30").plusDays(20));
        assertThat(e.status()).isEqualTo(EarningsCalendar.Status.EXPECTED);
    }

    @Test
    @DisplayName("With no measured habit the window is the regulation, and says it is assumed")
    void fallsBackToTheRegulation() {
        var e = EarningsCalendar.next(List.of(filed("2026-06-30", 40)), LocalDate.parse("2026-11-01"));

        // One filing is below MIN_LAG_SAMPLES, so there is no company-specific pattern.
        assertThat(e.typicalLagDays()).isNull();
        assertThat(e.estimated()).isTrue();
        assertThat(e.windowEnd())
                .isEqualTo(LocalDate.parse("2026-09-30").plusDays(EarningsCalendar.QUARTERLY_DEADLINE_DAYS));
        assertThat(e.text()).contains("SEBI allows");
    }

    @Test
    @DisplayName("The March year-end gets the 60-day audited deadline, not 45")
    void annualDeadlineIsLonger() {
        // Latest filed quarter ends December, so the next one is the audited March year-end.
        var e = EarningsCalendar.next(List.of(filed("2026-12-31", 40)), LocalDate.parse("2027-04-01"));

        assertThat(e.nextQuarterEnd()).isEqualTo(LocalDate.parse("2027-03-31"));
        assertThat(e.windowEnd())
                .isEqualTo(LocalDate.parse("2027-03-31").plusDays(EarningsCalendar.ANNUAL_DEADLINE_DAYS));
    }

    // ------------------------------------------------------------------ the statuses

    @Test
    @DisplayName("Before the quarter has ended there is nothing to be late about")
    void awaitingQuarterEnd() {
        var e = EarningsCalendar.next(series(40, 41, 39), LocalDate.parse("2026-08-01"));
        assertThat(e.status()).isEqualTo(EarningsCalendar.Status.AWAITING_QUARTER_END);
    }

    @Test
    @DisplayName("Inside the window the status is EXPECTED")
    void expected() {
        // Next quarter ends 30-Sep-2026; a company filing around day 40 is expected in early Nov.
        var e = EarningsCalendar.next(series(40, 41, 39), LocalDate.parse("2026-11-05"));
        assertThat(e.status()).isEqualTo(EarningsCalendar.Status.EXPECTED);
    }

    @Test
    @DisplayName("PAST_DUE says it cannot tell a late company from an uncaptured result")
    void pastDueIsHonestAboutWhichItIs() {
        var e = EarningsCalendar.next(series(40, 41, 39), LocalDate.parse("2027-01-15"));

        assertThat(e.status()).isEqualTo(EarningsCalendar.Status.PAST_DUE);
        // The distinction matters: one is a fact about the business, the other about the app.
        assertThat(e.text()).contains("has not published").contains("has not captured");
    }

    @Test
    @DisplayName("No filing on record is NOT_MEASURED, with nothing to count from")
    void notMeasured() {
        assertThat(EarningsCalendar.next(List.of(), LocalDate.parse("2026-08-15")).status())
                .isEqualTo(EarningsCalendar.Status.NOT_MEASURED);
        assertThat(EarningsCalendar.next(null, LocalDate.parse("2026-08-15")).status())
                .isEqualTo(EarningsCalendar.Status.NOT_MEASURED);
    }

    @Test
    @DisplayName("An absurd lag is a bad date or a backfill artefact, not a filing habit")
    void insaneLagsAreIgnored() {
        // A lag of a year means the stored date is wrong, not that the company took a year.
        List<QuarterlyResultEntity> rows = series(40, 41);
        rows.add(filed("2024-06-30", 400));

        assertThat(EarningsCalendar.lagSamples(rows)).isEqualTo(2);
    }
}
