package com.example.trading.funds;

import com.example.trading.funds.FundVocabulary.FundRecordVerdict;
import com.example.trading.funds.RollingWindowRecord.Benchmark;
import com.example.trading.funds.RollingWindowRecord.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §52.8 - a fund's record over rolling windows, counted rather than averaged.
 *
 * <p>The first case is the whole design. The rest are the refusals.
 */
class RollingWindowRecordTest {

    private static final int W = 36;
    private static final int MIN = 36;

    @Test
    @DisplayName("a fund whose AVERAGE excess is positive on one spectacular window still fails the count")
    void oneGreatWindowDoesNotCarryTheRecord() {
        // Eight years. The fund triples in one year and then trails by a little every month after.
        // Its mean excess return is strongly positive; it was ahead in a minority of windows.
        List<Point> fund = new ArrayList<>();
        double nav = 100;
        for (int i = 0; i <= 96; i++) {
            YearMonth m = YearMonth.of(2018, 4).plusMonths(i);
            if (i >= 12 && i < 24) {
                nav *= 1.0959;   // the spectacular year
            } else {
                nav *= 1.0020;   // and then a slow trail
            }
            fund.add(new Point(m, BigDecimal.valueOf(nav)));
        }
        Benchmark steady = flat("the category median", 6.0);

        RollingWindowRecord.Record r = RollingWindowRecord.measure(fund, steady, W, MIN);

        assertThat(r.windowsMeasured()).isGreaterThan(20);
        assertThat(r.shareAhead())
                .as("the mean is flattering; the count is not")
                .isLessThan(RollingWindowRecord.REQUIRED_SHARE);
        assertThat(r.verdict()).isNotEqualTo(FundRecordVerdict.AHEAD_OF_BENCHMARK);

        // And the average really is positive, which is the point of the case.
        double meanExcess = r.windows().stream().mapToDouble(RollingWindowRecord.Window::excessPp)
                .average().orElse(0);
        assertThat(meanExcess).isPositive();
    }

    @Test
    @DisplayName("windowsMeasured is not a sample size, and independentWindows says so")
    void overlappingWindowsAreNotIndependent() {
        List<Point> fund = compounding(97, 12.0);

        RollingWindowRecord.Record r = RollingWindowRecord.measure(fund, flat("median", 8.0), W, MIN);

        assertThat(r.windowsMeasured()).isGreaterThan(50);
        assertThat(r.independentWindows())
                .as("~97 months of history holds two non-overlapping 36-month windows, not sixty")
                .isEqualTo(2);
        assertThat(r.reason()).contains("behaves like a sample size");
    }

    @Test
    @DisplayName("the word \"consistently\" is withheld below two independent windows")
    void consistentlyNeedsTwoIndependentWindows() {
        // 60 months: 24 overlapping windows, but only one non-overlapping one.
        List<Point> shortish = compounding(60, 18.0);
        RollingWindowRecord.Record one = RollingWindowRecord.measure(shortish, flat("median", 6.0), W, MIN);

        assertThat(one.verdict()).isEqualTo(FundRecordVerdict.AHEAD_OF_BENCHMARK);
        assertThat(one.windowsMeasured()).isGreaterThan(20);
        assertThat(one.independentWindows()).isEqualTo(1);
        assertThat(one.mayUseConsistently())
                .as("twenty-four overlapping windows drawn from five years is not a consistent record")
                .isFalse();

        RollingWindowRecord.Record many =
                RollingWindowRecord.measure(compounding(97, 18.0), flat("median", 6.0), W, MIN);
        assertThat(many.mayUseConsistently()).isTrue();
    }

    @Test
    @DisplayName("a price index costs the fund the dividend haircut, a peer median does not")
    void triHaircutAppliesToAnIndexAndNotToPeers() {
        // Ahead by exactly 0.8 points a year - inside the 1.25-point haircut.
        List<Point> fund = compounding(97, 8.8);

        RollingWindowRecord.Record vsIndex =
                RollingWindowRecord.measure(fund, priceIndex("the Nifty 50", 8.0), W, MIN);
        RollingWindowRecord.Record vsPeers =
                RollingWindowRecord.measure(fund, flat("the category median", 8.0), W, MIN);

        assertThat(vsIndex.triHaircutPp()).isEqualTo(RollingWindowRecord.TRI_HAIRCUT_PP);
        assertThat(vsIndex.windowsAhead())
                .as("0.8 points of excess over a price index is inside the dividends that index omits")
                .isZero();
        assertThat(vsIndex.reason()).contains("overstated in the fund's favour");

        assertThat(vsPeers.triHaircutPp())
                .as("both sides are NAVs net of their own costs - there is no bias to correct")
                .isZero();
        assertThat(vsPeers.windowsAhead()).isEqualTo(vsPeers.windowsMeasured());
    }

    @Test
    @DisplayName("the raw excess is published rather than quietly reduced by the haircut")
    void haircutIsAWinConditionNotASubtraction() {
        List<Point> fund = compounding(97, 8.8);

        RollingWindowRecord.Record r =
                RollingWindowRecord.measure(fund, priceIndex("the Nifty 50", 8.0), W, MIN);

        assertThat(r.medianExcessPp())
                .as("the number shown is what was measured; the haircut is the bar, not a deduction")
                .isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @DisplayName("too little history is NOT_MEASURED - and says how much is on file")
    void shortHistoryIsNotMeasured() {
        RollingWindowRecord.Record r =
                RollingWindowRecord.measure(compounding(20, 15.0), flat("median", 8.0), W, MIN);

        assertThat(r.verdict()).isEqualTo(FundRecordVerdict.NOT_MEASURED);
        assertThat(r.shareAhead()).as("null, never 0.0 - nothing was measured").isNull();
        assertThat(r.reason()).contains("20 month-end observations");
    }

    @Test
    @DisplayName("no benchmark is NOT_COMPARABLE, never NOT_MEASURED - the fund's record is intact")
    void missingBenchmarkDoesNotDiscardTheFundsRecord() {
        RollingWindowRecord.Record r = RollingWindowRecord.measure(compounding(97, 15.0), null, W, MIN);

        assertThat(r.verdict()).isEqualTo(FundRecordVerdict.NOT_COMPARABLE);
        assertThat(r.monthsOnFile()).isEqualTo(97);
        assertThat(r.reason()).contains("not data about the fund");
        assertThat(r.mayUseConsistently()).isFalse();
    }

    @Test
    @DisplayName("a gap in the series never produces a 36-month figure measured over more months")
    void gapsDoNotStretchAWindow() {
        // 97 months with a nine-month hole. Stepping by index rather than by calendar would pair
        // points 36 apart in the list but 45 apart on the calendar, and report the result as a
        // three-year rate - B-047's family, and B-060's.
        List<Point> withGap = new ArrayList<>();
        double nav = 100;
        for (int i = 0; i <= 96; i++) {
            YearMonth m = YearMonth.of(2018, 4).plusMonths(i);
            nav *= Math.pow(1.15, 1.0 / 12);
            if (i >= 40 && i < 49) {
                continue;
            }
            withGap.add(new Point(m, BigDecimal.valueOf(nav)));
        }

        RollingWindowRecord.Record r = RollingWindowRecord.measure(withGap, flat("median", 8.0), W, MIN);

        assertThat(r.windows()).allSatisfy(w -> assertThat(
                RollingWindowRecord.monthsBetween(w.from(), w.to())).isEqualTo(W));
    }

    @Test
    @DisplayName("a benchmark that cannot answer a window drops it rather than scoring against zero")
    void benchmarkReturningNullDropsTheWindow() {
        Benchmark silent = new Benchmark() {
            @Override
            public String label() {
                return "a category with too few peers";
            }

            @Override
            public boolean appliesTriHaircut() {
                return false;
            }

            @Override
            public Double cagrPctFor(YearMonth from, YearMonth to) {
                return null;
            }
        };

        RollingWindowRecord.Record r = RollingWindowRecord.measure(compounding(97, 15.0), silent, W, MIN);

        assertThat(r.windowsMeasured()).isZero();
        assertThat(r.verdict())
                .as("a silent benchmark leaves the fund uncompared, not beaten")
                .isEqualTo(FundRecordVerdict.NOT_COMPARABLE);
    }

    @Test
    @DisplayName("a non-positive NAV is dropped rather than put under a root")
    void nonPositiveNavsAreDropped() {
        List<Point> fund = new ArrayList<>(compounding(97, 12.0));
        fund.set(10, new Point(fund.get(10).month(), BigDecimal.ZERO));

        RollingWindowRecord.Record r = RollingWindowRecord.measure(fund, flat("median", 8.0), W, MIN);

        assertThat(r.monthsOnFile()).isEqualTo(96);
        assertThat(r.windows()).allSatisfy(w -> {
            assertThat(w.fundCagrPct()).isNotNaN();
            assertThat(w.benchmarkCagrPct()).isNotNaN();
        });
    }

    // ------------------------------------------------------------------ helpers

    private static List<Point> compounding(int months, double annualPct) {
        List<Point> out = new ArrayList<>();
        double nav = 100;
        double step = Math.pow(1 + annualPct / 100, 1.0 / 12);
        for (int i = 0; i < months; i++) {
            out.add(new Point(YearMonth.of(2018, 4).plusMonths(i), BigDecimal.valueOf(nav)));
            nav *= step;
        }
        return out;
    }

    private static Benchmark flat(String label, double annualPct) {
        return benchmark(label, annualPct, false);
    }

    private static Benchmark priceIndex(String label, double annualPct) {
        return benchmark(label, annualPct, true);
    }

    private static Benchmark benchmark(String label, double annualPct, boolean haircut) {
        return new Benchmark() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public boolean appliesTriHaircut() {
                return haircut;
            }

            @Override
            public Double cagrPctFor(YearMonth from, YearMonth to) {
                return annualPct;
            }
        };
    }
}
