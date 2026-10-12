package com.example.trading.funds;

import com.example.trading.funds.FundVocabulary.FundRecordVerdict;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fund's record over rolling windows, <b>counted rather than averaged</b> (SPEC §52.8).
 *
 * <p>This is §43's idiom moved from years to windows, and it is the whole measurement design. An
 * <em>average</em> excess return lets one spectacular window carry a decade: a fund that beat its
 * benchmark by 30 points in 2020 and trailed by 2 points in every year since still shows a positive
 * average, and every fund factsheet in the country is built that way. Counting asks a different and
 * much harder question - <em>in how many of the windows it has lived through was it ahead?</em>
 *
 * <p><b>The trap one level down, which §43 does not have.</b> §43 counts <em>years</em>, and years
 * are independent. Monthly rolling windows are not: from 2018-04 there are about a hundred months,
 * so three-year windows stepped monthly give roughly <b>67 windows and fewer than 3 independent
 * ones</b>. {@link Record#windowsMeasured()} and {@link Record#independentWindows()} are therefore
 * always reported together, no t-statistic or significance language is ever produced here, and
 * {@link Record#mayUseConsistently()} is false below two independent windows - which means the
 * five-year rolling record <b>cannot exist yet</b> and the three-year window leads. Reading the
 * window count as a sample size is Gotcha 92 exactly, in a new asset class.
 *
 * <p><b>Why the benchmark is a function of the window and not a series.</b> The primary comparator
 * here is the median of a fund's own SEBI category, and a category's membership changes every year -
 * funds launch, merge and wind up. Building one index series out of it would have to pick a base
 * month, which silently excludes every peer that did not exist then and tilts the comparison toward
 * long-lived survivors. Asking the benchmark for its rate <em>over exactly this window</em> lets the
 * peer set be whatever actually existed across those months, which is the honest comparison.
 *
 * <p><b>The TRI haircut, and why it is a win condition rather than a subtraction.</b> SEBI mandates
 * Total Return Index benchmarks, which include dividends. Where this app holds only a price index,
 * the benchmark is understated by roughly the dividend yield - so every excess return against it is
 * overstated <em>in the fund's favour</em>, the worst possible direction. Quietly subtracting it
 * would bury the adjustment inside a number; instead the raw excess is published and the haircut is
 * the bar a window must clear, so a fund ahead by 0.8 points reads as <em>behind</em> and the reason
 * says why. A peer-median benchmark gets <b>no haircut</b>: both sides are net asset values net of
 * their own costs, so there is no bias to correct and applying one would invent a penalty.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class RollingWindowRecord {

    private RollingWindowRecord() {}

    /**
     * Share of windows a fund must be ahead in to read {@code AHEAD_OF_BENCHMARK}.
     *
     * <p>0.70, the same figure and the same reasoning as §43's {@code REQUIRED_SHARE}: not 1.0,
     * because a fund that was never once behind has not met a bad market and that selects for short
     * histories over durable ones, and not a bare majority, because a fund spends more than half of
     * a cycle in the half of it that suits its style. It is the one threshold here set on judgement
     * rather than on a measured percentile, for the reason §43.4 gives - the cross-section to
     * calibrate against does not exist until the backfill converges.
     */
    public static final double REQUIRED_SHARE = 0.70;

    /** Below this share the record reads as behind rather than mixed. */
    public static final double BEHIND_SHARE = 0.30;

    /**
     * Percentage points deducted from a price-index benchmark before a window counts as a win,
     * standing in for the dividends a Total Return Index would have included.
     *
     * <p>1.25 is the middle of the ~1.0-1.5% range Indian large-cap dividend yields have run at. It
     * is an adjustment made in the open, not a measurement, and every surface showing a benchmark
     * comparison must state it and its direction (SPEC §52.11, the benchmark-bias rule).
     */
    public static final double TRI_HAIRCUT_PP = 1.25;

    /** Two independent windows before the word "consistently" is available to any caller. */
    public static final int MIN_INDEPENDENT_FOR_CONSISTENCY = 2;

    /** One month-end observation. */
    public record Point(YearMonth month, BigDecimal nav) {}

    /** What the fund is measured against, asked window by window. */
    public interface Benchmark {

        String label();

        /** True for a price index, false for a peer median - see the class javadoc. */
        boolean appliesTriHaircut();

        /**
         * Annualised percent over exactly this window, or <b>null</b> when this benchmark cannot
         * answer for it - never zero, which would score the fund against a flat line.
         */
        Double cagrPctFor(YearMonth from, YearMonth to);
    }

    /** A benchmark backed by its own month-end series, e.g. an index this app stores. */
    public static Benchmark fromSeries(String label, List<Point> series, boolean triHaircut) {
        Map<YearMonth, BigDecimal> byMonth = byMonth(series);
        return new Benchmark() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public boolean appliesTriHaircut() {
                return triHaircut;
            }

            @Override
            public Double cagrPctFor(YearMonth from, YearMonth to) {
                return cagrPct(byMonth.get(from), byMonth.get(to), monthsBetween(from, to));
            }
        };
    }

    public record Window(YearMonth from, YearMonth to, double fundCagrPct, double benchmarkCagrPct,
                         double excessPp, boolean ahead) {}

    /**
     * @param windowsMeasured    how many rolling windows could be computed - <b>not a sample size</b>
     * @param independentWindows how many non-overlapping windows the history holds - the figure that
     *                           does behave like a sample size, and it is small
     * @param shareAhead         null when nothing was measured, never 0.0
     */
    public record Record(
            int windowMonths,
            int monthsOnFile,
            int windowsMeasured,
            int independentWindows,
            int windowsAhead,
            Double shareAhead,
            Double medianExcessPp,
            double triHaircutPp,
            String benchmarkLabel,
            FundRecordVerdict verdict,
            String reason,
            List<Window> windows
    ) {
        /**
         * Whether a caller may use the word "consistently" about this record.
         *
         * <p>False below {@link #MIN_INDEPENDENT_FOR_CONSISTENCY} independent windows, however many
         * overlapping ones were measured. Sixty-seven overlapping three-year windows drawn from
         * eight years of history carry about two and a half years of independent information, and a
         * word like "consistently" spends the credibility of the larger number.
         */
        public boolean mayUseConsistently() {
            return independentWindows >= MIN_INDEPENDENT_FOR_CONSISTENCY
                    && verdict != FundRecordVerdict.NOT_MEASURED
                    && verdict != FundRecordVerdict.NOT_COMPARABLE;
        }
    }

    /**
     * Measure one fund against one benchmark.
     *
     * @param fund         the fund's month-end series, any order, gaps allowed
     * @param benchmark    what to measure against; null yields {@code NOT_COMPARABLE}
     * @param windowMonths window length, e.g. 36
     * @param minMonths    minimum observations before anything is published
     */
    public static Record measure(List<Point> fund, Benchmark benchmark, int windowMonths,
                                 int minMonths) {
        Map<YearMonth, BigDecimal> fundByMonth = byMonth(fund);
        List<YearMonth> months = fundByMonth.keySet().stream().sorted().toList();

        if (months.size() < minMonths) {
            return bare(windowMonths, months.size(), FundRecordVerdict.NOT_MEASURED, null,
                    "only " + months.size() + " month-end observations are on file, and " + minMonths
                            + " are needed before any rolling figure means anything");
        }
        if (benchmark == null) {
            // Measured fine, nothing to grade it against. Deliberately NOT folded into
            // NOT_MEASURED, which would throw away the fund's own good record because this app has
            // not loaded an index (Gotcha 121).
            return bare(windowMonths, months.size(), FundRecordVerdict.NOT_COMPARABLE, null,
                    "this fund has " + months.size() + " month-end observations on file. What is "
                            + "missing is something appropriate to measure it against, not data "
                            + "about the fund");
        }

        double haircut = benchmark.appliesTriHaircut() ? TRI_HAIRCUT_PP : 0.0;
        List<Window> windows = new ArrayList<>();

        for (int i = 0; i + windowMonths < months.size(); i++) {
            YearMonth from = months.get(i);
            YearMonth to = months.get(i + windowMonths);
            // Only a window whose ends are exactly windowMonths apart on the calendar is a window
            // of that length. Without this a gap in the series silently produces a "three-year"
            // figure measured over four - B-047's family, and the same mismatch between a window
            // and its period that made B-060's monthly RSI return a constant.
            if (monthsBetween(from, to) != windowMonths) {
                continue;
            }
            Double fundCagr = cagrPct(fundByMonth.get(from), fundByMonth.get(to), windowMonths);
            Double benchCagr = benchmark.cagrPctFor(from, to);
            if (fundCagr == null || benchCagr == null) {
                continue;
            }
            double excess = fundCagr - benchCagr;
            windows.add(new Window(from, to, fundCagr, benchCagr, excess, excess >= haircut));
        }

        if (windows.isEmpty()) {
            return bare(windowMonths, months.size(), FundRecordVerdict.NOT_COMPARABLE,
                    benchmark.label(),
                    "the fund has " + months.size() + " observations but " + benchmark.label()
                            + " could not answer for any unbroken " + windowMonths
                            + "-month window inside them");
        }

        int ahead = (int) windows.stream().filter(Window::ahead).count();
        double share = (double) ahead / windows.size();
        int independent = (months.size() - 1) / windowMonths;
        double medianExcess = median(windows.stream().map(Window::excessPp).sorted().toList());

        FundRecordVerdict verdict = share >= REQUIRED_SHARE ? FundRecordVerdict.AHEAD_OF_BENCHMARK
                : share <= BEHIND_SHARE ? FundRecordVerdict.BEHIND_BENCHMARK
                : FundRecordVerdict.MIXED_RECORD;

        StringBuilder reason = new StringBuilder();
        reason.append("ahead of ").append(benchmark.label()).append(" in ").append(ahead)
                .append(" of ").append(windows.size()).append(" rolling ").append(windowMonths)
                .append("-month windows");
        if (haircut > 0) {
            reason.append(", after deducting ").append(String.format("%.2f", haircut))
                    .append(" points a year from the index to stand in for the dividends a "
                            + "total-return version would have included - without that deduction "
                            + "every figure here would be overstated in the fund's favour");
        }
        reason.append(". Those windows overlap heavily: the history holds ").append(independent)
                .append(independent == 1 ? " independent window" : " independent windows")
                .append(", which is the number that behaves like a sample size");

        return new Record(windowMonths, months.size(), windows.size(), independent, ahead, share,
                medianExcess, haircut, benchmark.label(), verdict, reason.toString(),
                List.copyOf(windows));
    }

    /**
     * A series that is measurable but not comparable with its peers - an income-distribution series
     * against Growth ones, for instance. The months on file are still reported, because what is
     * missing is a like-for-like comparator rather than data about the fund.
     */
    public static Record notComparable(int windowMonths, int monthsOnFile, String reason) {
        return bare(windowMonths, monthsOnFile, FundRecordVerdict.NOT_COMPARABLE, null, reason);
    }

    private static Record bare(int windowMonths, int monthsOnFile, FundRecordVerdict verdict,
                               String label, String reason) {
        return new Record(windowMonths, monthsOnFile, 0, 0, 0, null, null, 0.0, label, verdict,
                reason, List.of());
    }

    private static Map<YearMonth, BigDecimal> byMonth(List<Point> points) {
        Map<YearMonth, BigDecimal> out = new LinkedHashMap<>();
        if (points == null) {
            return out;
        }
        for (Point p : points) {
            // A non-positive NAV is not a price - keeping it would put a negative under a root.
            if (p != null && p.month() != null && p.nav() != null && p.nav().signum() > 0) {
                out.put(p.month(), p.nav());
            }
        }
        return out;
    }

    static int monthsBetween(YearMonth a, YearMonth b) {
        return (b.getYear() - a.getYear()) * 12 + (b.getMonthValue() - a.getMonthValue());
    }

    /** Annualised compound rate in percent, or null when either end is missing or non-positive. */
    public static Double cagrPct(BigDecimal from, BigDecimal to, int months) {
        if (from == null || to == null || from.signum() <= 0 || to.signum() <= 0 || months <= 0) {
            return null;
        }
        double ratio = to.doubleValue() / from.doubleValue();
        return (Math.pow(ratio, 12.0 / months) - 1.0) * 100.0;
    }

    static double median(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
