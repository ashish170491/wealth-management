package com.example.trading.learning.validation;

import java.util.List;

/**
 * The small amount of statistics the learning substrate needs, in one pure place.
 *
 * <p>Every method here returns {@code null} rather than a neutral-looking number when its
 * input cannot support an answer. That is the single most important property in this file. A
 * correlation of 0.0 and "there was not enough data to compute a correlation" look identical
 * once they are in a table, and this system has twice spent months reading the second as the
 * first (Institutional Interest scoring a constant 40 for three months, monthly RSI returning a
 * constant 50.0 for the entire universe). SPEC §38 exists because of those two incidents.
 *
 * <p>No Spring, no state, no I/O.
 */
public final class Statistics {

    private Statistics() {
    }

    /** Why a correlation could not be produced. Distinguishes immature from broken. */
    public enum Unavailable {
        /** Fewer than three paired observations — genuinely wait for more data. */
        INSUFFICIENT_SAMPLES,
        /** Every stock scored the same. The signal is broken, not immature. */
        CONSTANT_SCORE,
        /** Every stock realised the same return. Implausible — suspect the price feed. */
        CONSTANT_RETURN
    }

    /** A correlation, or the reason there is not one. Exactly one field is non-null. */
    public record Correlation(Double value, Unavailable reason) {
        public boolean present() {
            return value != null;
        }
    }

    /**
     * Pearson correlation between the two columns of {@code xy}.
     *
     * <p>Used for the cross-sectional Information Coefficient within a single date, where both
     * columns are already comparable. Across dates it would be wrong: pooling many dates into
     * one correlation lets a market-wide move in one period dominate, which is how a signal can
     * look predictive for having been measured mostly during a rally.
     */
    public static Correlation pearson(List<double[]> xy) {
        int n = xy == null ? 0 : xy.size();
        if (n < 3) return new Correlation(null, Unavailable.INSUFFICIENT_SAMPLES);
        double sx = 0, sy = 0;
        for (double[] p : xy) {
            sx += p[0];
            sy += p[1];
        }
        double mx = sx / n, my = sy / n;
        double cov = 0, vx = 0, vy = 0;
        for (double[] p : xy) {
            double dx = p[0] - mx, dy = p[1] - my;
            cov += dx * dy;
            vx += dx * dx;
            vy += dy * dy;
        }
        if (vx <= 0) return new Correlation(null, Unavailable.CONSTANT_SCORE);
        if (vy <= 0) return new Correlation(null, Unavailable.CONSTANT_RETURN);
        return new Correlation(cov / Math.sqrt(vx * vy), null);
    }

    /**
     * Spearman rank correlation — Pearson over ranks, with ties sharing their average rank.
     *
     * <p>This is the right measure for a scoring engine and the reason it is here alongside
     * Pearson. A composite is an ordering device: the portfolio buys the top of the list, so
     * what matters is whether a higher score means a higher return, not whether the relationship
     * is linear. Pearson on raw values is dominated by a handful of extreme returns — one stock
     * that trebled can carry a whole panel — whereas ranks bound every stock's influence. The
     * existing per-dimension panel uses Pearson, which is left alone; the walk-forward harness
     * reports both so the two can be compared rather than silently disagreeing.
     */
    public static Correlation spearman(List<double[]> xy) {
        int n = xy == null ? 0 : xy.size();
        if (n < 3) return new Correlation(null, Unavailable.INSUFFICIENT_SAMPLES);
        double[] rx = ranks(column(xy, 0));
        double[] ry = ranks(column(xy, 1));
        List<double[]> ranked = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ranked.add(new double[]{rx[i], ry[i]});
        }
        return pearson(ranked);
    }

    private static double[] column(List<double[]> xy, int idx) {
        double[] out = new double[xy.size()];
        for (int i = 0; i < xy.size(); i++) out[i] = xy.get(i)[idx];
        return out;
    }

    /** Ranks, ascending, ties averaged. A tie block of three at positions 4-6 all get 5. */
    public static double[] ranks(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));

        double[] out = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[order[j + 1]] == values[order[i]]) j++;
            double avgRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) out[order[k]] = avgRank;
            i = j + 1;
        }
        return out;
    }

    /** Arithmetic mean, or null for an empty sample. */
    public static Double mean(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return null;
        double s = 0;
        for (double x : xs) s += x;
        return s / xs.size();
    }

    /**
     * Sample standard deviation (n−1 denominator), or null below two observations.
     *
     * <p>The n−1 denominator matters here rather than being a formality: the samples this class
     * sees are of size five or so, where the population formula understates the spread by
     * roughly a tenth and therefore overstates every t-statistic built on it.
     */
    public static Double stdDev(List<Double> xs) {
        if (xs == null || xs.size() < 2) return null;
        Double m = mean(xs);
        double ss = 0;
        for (double x : xs) ss += (x - m) * (x - m);
        return Math.sqrt(ss / (xs.size() - 1));
    }

    /**
     * Relative floor below which a spread is treated as zero.
     *
     * <p>Not decoration. Three readings of 0.05 do not sum and divide back to exactly 0.05 in
     * binary floating point, so the naive {@code sd <= 0} check let a sample with no real
     * variation through with a standard deviation around 1e-17 — and a t-statistic of about
     * 1e16, which clears every critical value ever tabulated. A promotion gate handed that
     * number would certify a variant on three identical observations. Caught by
     * {@code StatisticsTest.zeroSpreadHasNoTStatistic} before it could reach the gate.
     */
    private static final double RELATIVE_SPREAD_EPSILON = 1e-9;

    /**
     * One-sample t-statistic against a null of zero: {@code mean / (sd / sqrt(n))}.
     *
     * @return null below two observations, or when the spread is indistinguishable from zero.
     *         An enormous t-statistic from numbers that differ only in their last bits is not
     *         evidence of anything, and the whole purpose of this class is that an absent
     *         answer stays absent rather than arriving disguised as a decisive one
     */
    public static Double tStatistic(List<Double> xs) {
        if (xs == null || xs.size() < 2) return null;
        Double m = mean(xs);
        Double sd = stdDev(xs);
        if (m == null || sd == null || sd <= 0) return null;
        // Scaled to the magnitude of the data: an absolute floor would be wrong for rank
        // correlations, which legitimately live in the third decimal place.
        double scale = Math.max(Math.abs(m), 1e-12);
        if (sd <= scale * RELATIVE_SPREAD_EPSILON) return null;
        return m / (sd / Math.sqrt(xs.size()));
    }

    /**
     * Two-sided critical t-value at significance {@code alpha} with {@code df} degrees of
     * freedom, from a small lookup table over the range this system can actually reach.
     *
     * <p>A table rather than an inverse-CDF implementation, deliberately. The harness will have
     * between about three and fifteen independent periods for years; the table covers that
     * range exactly, and beyond it the normal approximation is used, which is accurate where it
     * is applied. An approximation that is wrong in the small-sample regime would be wrong
     * precisely where every decision this gate makes will be taken.
     *
     * @param df    degrees of freedom, i.e. observations − 1
     * @param alpha two-sided significance level, e.g. 0.05
     * @return the critical value, or null when {@code df < 1}
     */
    public static Double criticalT(int df, double alpha) {
        if (df < 1) return null;
        double[] levels = {0.20, 0.10, 0.05, 0.02, 0.01, 0.005, 0.002, 0.001};
        // Rows: df = 1..15, then 20, 30, 60, infinity. Standard two-sided critical values.
        double[][] table = {
                {3.078, 6.314, 12.706, 31.821, 63.657, 127.321, 318.309, 636.619}, // df 1
                {1.886, 2.920, 4.303, 6.965, 9.925, 14.089, 22.327, 31.599},
                {1.638, 2.353, 3.182, 4.541, 5.841, 7.453, 10.215, 12.924},
                {1.533, 2.132, 2.776, 3.747, 4.604, 5.598, 7.173, 8.610},
                {1.476, 2.015, 2.571, 3.365, 4.032, 4.773, 5.893, 6.869},
                {1.440, 1.943, 2.447, 3.143, 3.707, 4.317, 5.208, 5.959},
                {1.415, 1.895, 2.365, 2.998, 3.499, 4.029, 4.785, 5.408},
                {1.397, 1.860, 2.306, 2.896, 3.355, 3.833, 4.501, 5.041},
                {1.383, 1.833, 2.262, 2.821, 3.250, 3.690, 4.297, 4.781},
                {1.372, 1.812, 2.228, 2.764, 3.169, 3.581, 4.144, 4.587},
                {1.363, 1.796, 2.201, 2.718, 3.106, 3.497, 4.025, 4.437},
                {1.356, 1.782, 2.179, 2.681, 3.055, 3.428, 3.930, 4.318},
                {1.350, 1.771, 2.160, 2.650, 3.012, 3.372, 3.852, 4.221},
                {1.345, 1.761, 2.145, 2.624, 2.977, 3.326, 3.787, 4.140},
                {1.341, 1.753, 2.131, 2.602, 2.947, 3.286, 3.733, 4.073}, // df 15
                {1.325, 1.725, 2.086, 2.528, 2.845, 3.153, 3.552, 3.850}, // df 20
                {1.310, 1.697, 2.042, 2.457, 2.750, 3.030, 3.385, 3.646}, // df 30
                {1.296, 1.671, 2.000, 2.390, 2.660, 2.915, 3.232, 3.460}, // df 60
                {1.282, 1.645, 1.960, 2.326, 2.576, 2.807, 3.090, 3.291}  // normal
        };

        int col = 0;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < levels.length; i++) {
            double gap = Math.abs(levels[i] - alpha);
            if (gap < bestGap) {
                bestGap = gap;
                col = i;
            }
        }

        int row;
        if (df <= 15) row = df - 1;
        else if (df <= 20) row = 15;
        else if (df <= 30) row = 16;
        else if (df <= 60) row = 17;
        else row = 18;
        return table[row][col];
    }
}
