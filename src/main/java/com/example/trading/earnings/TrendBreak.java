package com.example.trading.earnings;

import java.util.List;

/**
 * "Did this quarter break the company's own recent trend?" — one rule table, two callers
 * (SPEC.md §50.3).
 *
 * <p>This app has no paid analyst-consensus feed, and §24 says so plainly rather than inventing
 * one. The honest substitute is a company's own momentum: fit a line through the three quarters
 * before this one, project the fourth, and measure how far the actual landed from it. A 30% profit
 * jump when the trend said +5% is meaningful whoever expected what.
 *
 * <p><b>Why this is a separate class.</b> The thresholds used to live as a private method inside
 * {@code NseDataService}, reached only through a live NSE fetch. Adding a second copy for the
 * stored-result read would be Gotcha 85 in its plainest form — two engines answering one question,
 * free to drift into disagreeing in identical words. Both callers now share this table: the live
 * analyst-signal bonus (§24) and the stored quarterly read (§50).
 *
 * <p>Pure: no clock, no I/O, no Spring.
 */
public final class TrendBreak {

    private TrendBreak() {
    }

    public static final String BIG_POSITIVE = "BIG_POSITIVE_BREAK";
    public static final String POSITIVE = "POSITIVE_BREAK";
    public static final String IN_LINE = "IN_LINE";
    public static final String NEGATIVE = "NEGATIVE_BREAK";
    public static final String BIG_NEGATIVE = "BIG_NEGATIVE_BREAK";
    public static final String INSUFFICIENT = "INSUFFICIENT_DATA";

    /** Beyond this, the quarter is a break rather than noise. */
    public static final double BREAK_PERCENT = 15.0;
    /** Beyond this, a big one. */
    public static final double BIG_BREAK_PERCENT = 30.0;

    /** Quarters needed before a projection can be made at all: three to fit, one to judge. */
    public static final int MIN_QUARTERS = 4;

    /**
     * The verdict for a surprise percentage. {@code null} in gives {@link #INSUFFICIENT} — an
     * unmeasurable quarter is never an in-line one (Gotcha 21).
     */
    public static String classify(Double surprisePct) {
        if (surprisePct == null) return INSUFFICIENT;
        if (surprisePct > BIG_BREAK_PERCENT) return BIG_POSITIVE;
        if (surprisePct > BREAK_PERCENT) return POSITIVE;
        if (surprisePct > -BREAK_PERCENT) return IN_LINE;
        if (surprisePct > -BIG_BREAK_PERCENT) return NEGATIVE;
        return BIG_NEGATIVE;
    }

    public static boolean isNegative(String verdict) {
        return NEGATIVE.equals(verdict) || BIG_NEGATIVE.equals(verdict);
    }

    public static boolean isPositive(String verdict) {
        return POSITIVE.equals(verdict) || BIG_POSITIVE.equals(verdict);
    }

    /**
     * Least-squares projection of the next value from an ordered (oldest-first) series.
     *
     * <p>Nulls are skipped rather than treated as zero — a quarter NSE did not tag is an unknown,
     * and feeding it in as 0 would drag the fitted line toward the axis and manufacture a break.
     * Returns null below two usable points, because a line through one point is not a trend.
     */
    public static Double project(List<Double> oldestFirst) {
        if (oldestFirst == null || oldestFirst.isEmpty()) return null;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        int n = 0;
        for (int i = 0; i < oldestFirst.size(); i++) {
            Double y = oldestFirst.get(i);
            if (y == null) continue;
            sumX += i;
            sumY += y;
            sumXY += i * y;
            sumXX += (double) i * i;
            n++;
        }
        if (n < 2) return null;
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0) return null;
        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        return intercept + slope * oldestFirst.size();
    }

    /**
     * How far {@code actual} landed from {@code projected}, as a percentage of the projection's
     * magnitude.
     *
     * <p>Divides by the absolute projection so that a company projected to lose money and losing
     * less still reads positive. Refuses a projection of ~0, where the percentage is unbounded and
     * says nothing about the business.
     */
    public static Double surprise(Double actual, Double projected) {
        if (actual == null || projected == null || Math.abs(projected) < 1e-6) return null;
        return (actual - projected) / Math.abs(projected) * 100.0;
    }
}
