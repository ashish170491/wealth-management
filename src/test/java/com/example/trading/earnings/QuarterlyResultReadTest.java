package com.example.trading.earnings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the quarterly result read (SPEC §50.3).
 *
 * <p>The properties defended here are the ones that stop the verdict flattering or alarming
 * without cause: a comparison across reporting bases is refused rather than converted, an
 * unmeasurable quarter is never called in-line, a loss outranks a good revenue line, a recovery
 * from a loss quotes no growth rate, and none of the five words the feature can say is an
 * instruction to transact.
 */
class QuarterlyResultReadTest {

    // ------------------------------------------------------------------ builders

    /** A quarter with the fields the read actually uses. Basis defaults to consolidated. */
    private static QuarterlyResultEntity q(String end, Double revenue, Double profit) {
        LocalDate d = LocalDate.parse(end);
        Double margin = (revenue != null && revenue != 0 && profit != null)
                ? profit / revenue * 100.0 : null;
        return QuarterlyResultEntity.builder()
                .symbol("TEST")
                .quarterEnd(d)
                .fiscalLabel(FiscalQuarter.label(d))
                .revenue(revenue)
                .profit(profit)
                .netMargin(margin)
                .consolidated(true)
                .availableFrom(d.plusDays(40))
                .availableFromEstimated(false)
                .build();
    }

    /** Four consecutive quarters ending on the given date, flat at the given level. */
    private static List<QuarterlyResultEntity> flatHistory(String latestEnd, double rev, double profit) {
        List<QuarterlyResultEntity> rows = new ArrayList<>();
        LocalDate d = LocalDate.parse(latestEnd);
        for (int i = 0; i < 8; i++) {
            rows.add(q(d.toString(), rev, profit));
            d = d.minusMonths(3).with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        }
        return rows;
    }

    // ------------------------------------------------------------------ refusals

    @Nested
    @DisplayName("It refuses rather than guesses")
    class Refusals {

        @Test
        @DisplayName("No history at all is NOT_MEASURED, never IN_LINE")
        void noHistory() {
            var r = QuarterlyResultRead.of("TEST", List.of());
            assertThat(r.verdict()).isEqualTo(QuarterlyResultRead.Verdict.NOT_MEASURED);
            assertThat(r.measured()).isFalse();
            // The wording has to say whose gap it is.
            assertThat(r.headline().toLowerCase(Locale.ROOT)).contains("captured");
        }

        @Test
        @DisplayName("One quarter on file cannot be judged — fewer than two signals is NOT_MEASURED")
        void singleQuarter() {
            var r = QuarterlyResultRead.of("TEST", List.of(q("2026-06-30", 1000.0, 100.0)));
            assertThat(r.verdict()).isEqualTo(QuarterlyResultRead.Verdict.NOT_MEASURED);
            assertThat(r.measuredSignals()).isLessThan(QuarterlyResultRead.MIN_MEASURED_SIGNALS);
            // All four are still reported, so the reader can see what could not be checked.
            assertThat(r.signals()).hasSize(QuarterlyResultRead.TOTAL_SIGNALS);
        }

        @Test
        @DisplayName("A consolidated quarter is never compared with a standalone one")
        void basisMismatchIsRefused() {
            // Revenue halves purely because the year-ago quarter was filed standalone. Comparing
            // them would manufacture a collapse that never happened (Gotcha 73).
            var latest = q("2026-06-30", 500.0, 50.0);
            latest.setConsolidated(false);
            var yearAgo = q("2025-06-30", 1000.0, 100.0);
            yearAgo.setConsolidated(true);

            var r = QuarterlyResultRead.of("TEST", List.of(latest, yearAgo));

            assertThat(r.revenueYoyPercent()).isNull();
            assertThat(r.profitYoyPercent()).isNull();
            assertThat(r.basisNote()).contains("standalone").contains("consolidated");
            var revenue = signal(r, "REVENUE_YOY");
            assertThat(revenue.status()).isEqualTo(QuarterlyResultRead.Status.NOT_MEASURED);
            assertThat(revenue.text()).contains("different reporting bases");
        }

        @Test
        @DisplayName("An unstated basis is assumed comparable, and the assumption is stated")
        void unknownBasisSaysSo() {
            var latest = q("2026-06-30", 1200.0, 140.0);
            latest.setConsolidated(null);
            var yearAgo = q("2025-06-30", 1000.0, 100.0);

            var r = QuarterlyResultRead.of("TEST", List.of(latest, yearAgo));

            assertThat(r.revenueYoyPercent()).isEqualTo(20.0);
            assertThat(r.basisNote()).contains("did not state");
        }

        @Test
        @DisplayName("A gap in the captured quarters is not fitted as a trend")
        void nonConsecutiveQuartersRefuseTheTrend() {
            // Four quarters on file, but one season is missing, so the three before the latest are
            // not consecutive. Fitting a line through them would invent momentum.
            var rows = List.of(
                    q("2026-06-30", 1000.0, 100.0),
                    q("2025-12-31", 900.0, 90.0),
                    q("2025-09-30", 880.0, 88.0),
                    q("2025-06-30", 860.0, 86.0));

            var r = QuarterlyResultRead.of("TEST", rows);

            var trend = signal(r, "VS_OWN_TREND");
            assertThat(trend.status()).isEqualTo(QuarterlyResultRead.Status.NOT_MEASURED);
            assertThat(trend.text()).contains("not consecutive");
        }
    }

    // ------------------------------------------------------------------ losses

    @Nested
    @DisplayName("A loss outranks the count")
    class Losses {

        @Test
        @DisplayName("Swinging from profit to loss is CONCERNING even when sales grew strongly")
        void swingToLoss() {
            // Revenue up 50% — a count of good signals alone would not call this out, which is
            // exactly the case the loss rule exists for.
            var rows = List.of(
                    q("2026-06-30", 1500.0, -80.0),
                    q("2025-06-30", 1000.0, 100.0));

            var r = QuarterlyResultRead.of("TEST", rows);

            assertThat(r.verdict()).isEqualTo(QuarterlyResultRead.Verdict.CONCERNING);
            assertThat(r.headline()).contains("loss-making");
        }

        @Test
        @DisplayName("A loss that is narrowing is WEAK, not CONCERNING")
        void narrowingLoss() {
            var rows = List.of(
                    q("2026-06-30", 1000.0, -50.0),
                    q("2025-06-30", 900.0, -100.0));

            var r = QuarterlyResultRead.of("TEST", rows);

            assertThat(r.verdict()).isEqualTo(QuarterlyResultRead.Verdict.WEAK);
        }

        @Test
        @DisplayName("A widening loss is CONCERNING")
        void wideningLoss() {
            var rows = List.of(
                    q("2026-06-30", 1000.0, -150.0),
                    q("2025-06-30", 900.0, -100.0));

            assertThat(QuarterlyResultRead.of("TEST", rows).verdict())
                    .isEqualTo(QuarterlyResultRead.Verdict.CONCERNING);
        }

        @Test
        @DisplayName("A loss with nothing to compare it against is WEAK, not CONCERNING")
        void lossWithNoComparison() {
            var r = QuarterlyResultRead.of("TEST", List.of(q("2026-06-30", 1000.0, -50.0)));
            assertThat(r.verdict()).isEqualTo(QuarterlyResultRead.Verdict.WEAK);
        }

        @Test
        @DisplayName("Returning to profit from a loss quotes no growth rate")
        void recoveryQuotesNoPercentage() {
            // -100 to +10 is not "110% growth"; the percentage is meaningless and the honest
            // answer is the outcome in words (B-113's rule, one scale down).
            var rows = List.of(
                    q("2026-06-30", 1000.0, 10.0),
                    q("2025-06-30", 900.0, -100.0));

            var r = QuarterlyResultRead.of("TEST", rows);

            assertThat(r.profitYoyPercent()).isNull();
            var profit = signal(r, "PROFIT_YOY");
            assertThat(profit.status()).isEqualTo(QuarterlyResultRead.Status.STRONG);
            assertThat(profit.text()).contains("No growth rate is quoted");
        }
    }

    // ------------------------------------------------------------------ the count

    @Nested
    @DisplayName("It counts signals rather than averaging them")
    class Counting {

        @Test
        @DisplayName("A flat business is IN_LINE, not STRONG and not WEAK")
        void flatIsInLine() {
            var r = QuarterlyResultRead.of("TEST", flatHistory("2026-06-30", 1000.0, 100.0));
            assertThat(r.verdict()).isEqualTo(QuarterlyResultRead.Verdict.IN_LINE);
            assertThat(r.measuredSignals()).isEqualTo(QuarterlyResultRead.TOTAL_SIGNALS);
        }

        @Test
        @DisplayName("Growth on every measure reads STRONG")
        void growthIsStrong() {
            // Latest quarter well ahead of a rising series: sales and profit both up strongly
            // year-on-year, the margin wider, and the quarter above its own trend line.
            List<QuarterlyResultEntity> rows = new ArrayList<>(List.of(
                    q("2026-06-30", 1500.0, 260.0),
                    q("2026-03-31", 1150.0, 130.0),
                    q("2025-12-31", 1100.0, 120.0),
                    q("2025-09-30", 1050.0, 112.0),
                    q("2025-06-30", 1000.0, 100.0)));

            var r = QuarterlyResultRead.of("TEST", rows);

            assertThat(r.verdict()).isEqualTo(QuarterlyResultRead.Verdict.STRONG);
            assertThat(r.revenueYoyPercent()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("One enormous revenue jump does not carry a quarter whose profit collapsed")
        void oneGoodLegDoesNotCarryTheQuarter() {
            // Sales +100%, profit -60%, margin far narrower. An average over the four checks
            // could land this at neutral; counting cannot (Gotcha 103).
            var rows = List.of(
                    q("2026-06-30", 2000.0, 40.0),
                    q("2025-06-30", 1000.0, 100.0));

            var r = QuarterlyResultRead.of("TEST", rows);

            assertThat(r.revenueYoyPercent()).isEqualTo(100.0);
            assertThat(r.verdict())
                    .isIn(QuarterlyResultRead.Verdict.WEAK, QuarterlyResultRead.Verdict.CONCERNING);
        }

        @Test
        @DisplayName("Quarter-on-quarter is reported but never decides the verdict")
        void qoqIsNotASignal() {
            // A seasonal business: this quarter is far below the previous one but ahead of the
            // same quarter last year. Reading QoQ as a signal would call a good year a bad one.
            var rows = List.of(
                    q("2026-06-30", 1100.0, 110.0),
                    q("2026-03-31", 2000.0, 300.0),
                    q("2025-06-30", 1000.0, 100.0));

            var r = QuarterlyResultRead.of("TEST", rows);

            assertThat(r.profitQoqPercent()).isNotNull().isLessThan(0.0);
            assertThat(r.verdict()).isNotIn(
                    QuarterlyResultRead.Verdict.WEAK, QuarterlyResultRead.Verdict.CONCERNING);
        }
    }

    // ------------------------------------------------------------------ vocabulary

    @Nested
    @DisplayName("The vocabulary contains no instruction to transact")
    class Vocabulary {

        @Test
        @DisplayName("No verdict name is a buy or a sell")
        void verdictNamesAreDescriptive() {
            for (QuarterlyResultRead.Verdict v : QuarterlyResultRead.Verdict.values()) {
                assertThat(v.name().toLowerCase(Locale.ROOT))
                        .doesNotContain("buy").doesNotContain("sell").doesNotContain("exit")
                        .doesNotContain("hold").doesNotContain("accumulate");
            }
        }

        @Test
        @DisplayName("No headline or signal text tells the investor to trade")
        void proseCarriesNoInstruction() {
            List<QuarterlyResultRead.Result> results = List.of(
                    QuarterlyResultRead.of("TEST", flatHistory("2026-06-30", 1000.0, 100.0)),
                    QuarterlyResultRead.of("TEST", List.of(
                            q("2026-06-30", 1500.0, -80.0), q("2025-06-30", 1000.0, 100.0))),
                    QuarterlyResultRead.of("TEST", List.of()));

            for (var r : results) {
                assertNoInstruction(r.headline());
                for (var s : r.signals()) {
                    assertNoInstruction(s.text());
                }
            }
        }

        private void assertNoInstruction(String text) {
            String t = text.toLowerCase(Locale.ROOT);
            assertThat(t).doesNotContain(" buy ").doesNotContain(" sell ")
                    .doesNotContain("book profit").doesNotContain("exit this")
                    .doesNotContain("add more");
        }
    }

    // ------------------------------------------------------------------ provenance

    @Test
    @DisplayName("The read carries the day the company published, not the quarter end")
    void carriesThePublicationDate() {
        var r = QuarterlyResultRead.of("TEST", flatHistory("2026-06-30", 1000.0, 100.0));
        // 40 days after the quarter end, per the builder — the point being that the two differ.
        assertThat(r.availableFrom()).isEqualTo(LocalDate.parse("2026-08-09"));
        assertThat(r.availableFrom()).isNotEqualTo(r.quarterEnd());
        assertThat(r.availableFromEstimated()).isFalse();
    }

    @Test
    @DisplayName("Every read reports how many of the four checks produced an answer")
    void coverageIsAlwaysReported() {
        var partial = QuarterlyResultRead.of("TEST", List.of(
                q("2026-06-30", 1200.0, 130.0), q("2025-06-30", 1000.0, 100.0)));

        assertThat(partial.totalSignals()).isEqualTo(QuarterlyResultRead.TOTAL_SIGNALS);
        assertThat(partial.measuredSignals()).isEqualTo(3);   // the trend needs four quarters
        assertThat(partial.signals()).hasSize(QuarterlyResultRead.TOTAL_SIGNALS);
        assertThat(signal(partial, "VS_OWN_TREND").status())
                .isEqualTo(QuarterlyResultRead.Status.NOT_MEASURED);
    }

    private static QuarterlyResultRead.Signal signal(QuarterlyResultRead.Result r, String key) {
        return r.signals().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }
}
