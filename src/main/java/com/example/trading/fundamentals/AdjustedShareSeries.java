package com.example.trading.fundamentals;

import java.util.ArrayList;
import java.util.List;

/**
 * A share-count history with bonus issues and splits divided out, and the refusals that protect it
 * (SPEC §57, rule VG-S1). Pure: no repository, no clock, no Spring.
 *
 * <p><b>Why it lives in {@code fundamentals}.</b> The face-value test and the simple-ratio grid
 * already exist as {@link CapitalAllocationRecord#corporateActionRatio} and that method is
 * package-private on purpose (it is the one place B-066's rule is written down). This class reuses
 * it for the face-value path and the strict grid rather than keeping a second copy that could
 * drift, and adds only what the valuation-gap lens needs on top: a wider tolerance for large
 * ratios, an <i>ambiguity refusal</i> for small ones, and three consistency refusals.
 *
 * <p><b>The rule, in order, for each year-on-year step in share count:</b>
 * <ol>
 *   <li>A change in face value <i>is</i> a split: the face-value ratio is the action. If the share
 *       count then disagrees with it by more than the non-action band, refuse.</li>
 *   <li>Otherwise a ratio of {@code minBonusRatio} or more within {@code gridTolerance} of a simple
 *       bonus ratio is divided out. (The older 0.05% tolerance refused real bonuses whenever an
 *       ESOP allotment landed in the same year - round-1 review.)</li>
 *   <li>A grid-like step of {@code ambiguousCeiling} or less with <b>no</b> face-value change is
 *       ambiguous - a 1:20 bonus and a 5% rights issue look identical - so the stock is
 *       <b>refused</b>, never guessed. Small ratios accepted as bonuses were how capital raises
 *       got divided out as free shares.</li>
 *   <li>Any other step within the non-action band is a real issue or buyback and stays in: EPS
 *       genuinely moves when the share count does.</li>
 *   <li>A step beyond the band that no rule explains is refused.</li>
 * </ol>
 * Three consistency refusals run first: a share count at or below 0.01 crore, a face value that
 * returns to an earlier value, and a one-year spike that reverses (a unit slip - SUNPHARMA read
 * 239.9, 0.000, 239.9). Face value and share count are <b>not independent evidence</b>: the count is
 * derived as paid-up capital over face value, so only the reversal checks can catch a mis-tag.
 */
public final class AdjustedShareSeries {

    private AdjustedShareSeries() {
    }

    public static final String SHARE_COUNT_INCONSISTENT = "SHARE_COUNT_INCONSISTENT";
    public static final String UNEXPLAINED_SHARE_STEP = "UNEXPLAINED_SHARE_STEP";
    public static final String AMBIGUOUS_SHARE_STEP = "AMBIGUOUS_SHARE_STEP";

    public static final String VIA_FACE_VALUE = "FACE_VALUE";
    public static final String VIA_RATIO_GRID = "RATIO_GRID";

    /** The tolerances, passed in so the numbers live in one fingerprinted place (ValuationGapRules). */
    public record Tolerances(double minBonusRatio, double gridTolerance,
                             double ambiguousCeiling, double nonActionBand) {
    }

    /** A bonus issue or split that was divided out. */
    public record Action(int fiscalYear, double ratio, String via) {
    }

    /**
     * One year. {@code factor} is the product of every action <i>after</i> this year: multiply the
     * year's share count by it to restate it on the latest year's share basis.
     */
    public record Point(int fiscalYear, Double shareCount, Double faceValue, double factor) {
        /** Shares in the latest year's terms (crore). */
        public Double adjustedShares() {
            return shareCount == null ? null : shareCount * factor;
        }
    }

    /** {@code problemCode} is null when the series is usable. */
    public record Result(List<Point> points, List<Action> actions, String problemCode, String problem) {
        public boolean usable() {
            return problemCode == null;
        }
    }

    /** Simple bonus/split ratios of 1.5 or more, and their inverses are derived. */
    private static final double[] GRID = {1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0};

    /**
     * @param rows contiguous years, oldest first, every share count present (the caller refuses a
     *             null first - this class does not repair it)
     */
    public static Result analyse(List<AnnualFundamentalsEntity> rows, Tolerances tol) {
        int n = rows.size();
        for (AnnualFundamentalsEntity r : rows) {
            Double sc = r.getShareCount();
            if (sc == null || sc <= 0.01) {
                return refuse(rows, SHARE_COUNT_INCONSISTENT, "FY" + r.getFiscalYear() + " share count is "
                        + (sc == null ? "missing" : sc + " crore - too small to be a listed company's "
                        + "(a unit slip)") + ".");
            }
        }

        String faceValueReturn = faceValueReturn(rows);
        if (faceValueReturn != null) {
            return refuse(rows, SHARE_COUNT_INCONSISTENT, faceValueReturn);
        }

        double[] step = new double[n];
        for (int i = 1; i < n; i++) {
            step[i] = rows.get(i).getShareCount() / rows.get(i - 1).getShareCount();
        }
        for (int i = 1; i + 1 < n; i++) {
            if (Math.abs(Math.log(step[i])) > 0.5 && Math.abs(Math.log(step[i] * step[i + 1])) < 0.1) {
                return refuse(rows, SHARE_COUNT_INCONSISTENT, "The share count jumps in FY"
                        + rows.get(i).getFiscalYear() + " and reverses in FY" + rows.get(i + 1).getFiscalYear()
                        + " - a one-year spike that undoes itself is a unit slip, not a corporate action.");
            }
        }

        List<Action> actions = new ArrayList<>();
        double[] ratio = new double[n];
        java.util.Arrays.fill(ratio, 1.0);
        for (int i = 1; i < n; i++) {
            AnnualFundamentalsEntity prev = rows.get(i - 1);
            AnnualFundamentalsEntity cur = rows.get(i);
            double rho = step[i];
            Double fvAction = faceValueAction(prev.getFaceValue(), cur.getFaceValue());
            String label = "FY" + prev.getFiscalYear() + " to FY" + cur.getFiscalYear();

            if (fvAction != null) {
                if (Math.abs(rho / fvAction - 1.0) > tol.nonActionBand()) {
                    return refuse(rows, UNEXPLAINED_SHARE_STEP, label + ": face value moved "
                            + fmt(prev.getFaceValue()) + " to " + fmt(cur.getFaceValue()) + " but the share count "
                            + "changed by " + fmt(rho) + "x, not the " + fmt(fvAction) + "x a split implies.");
                }
                ratio[i] = fvAction;
                actions.add(new Action(cur.getFiscalYear(), fvAction, VIA_FACE_VALUE));
                continue;
            }

            Double grid = null;
            // The cut-off is the ratio MINUS the grid tolerance, not the ratio itself: a genuine 1-for-2 bonus
            // reads 1.4999 in floating point and would otherwise fall through to the refusals (IOC, MANINFRA and
            // MOTHERSON all did, on the first run against the live table).
            if (rho >= tol.minBonusRatio() * (1.0 - tol.gridTolerance())) {
                grid = nearestGrid(rho, tol.gridTolerance());
                if (grid == null) {
                    return refuse(rows, UNEXPLAINED_SHARE_STEP, label + ": the share count rose " + fmt(rho)
                            + "x with no face-value change and no matching bonus ratio.");
                }
                ratio[i] = grid;
                actions.add(new Action(cur.getFiscalYear(), grid, VIA_RATIO_GRID));
                continue;
            }
            if (rho <= (1.0 / tol.minBonusRatio()) * (1.0 + tol.gridTolerance())) {
                Double inverse = nearestInverseGrid(rho, tol.gridTolerance());
                if (inverse == null) {
                    return refuse(rows, UNEXPLAINED_SHARE_STEP, label + ": the share count fell to " + fmt(rho)
                            + "x with no face-value change and no matching consolidation ratio.");
                }
                ratio[i] = inverse;
                actions.add(new Action(cur.getFiscalYear(), inverse, VIA_RATIO_GRID));
                continue;
            }
            if (rho > 1.0) {
                Double strict = CapitalAllocationRecord.corporateActionRatio(rho, null, null);
                if (strict != null && strict <= tol.ambiguousCeiling()) {
                    return refuse(rows, AMBIGUOUS_SHARE_STEP, label + ": the share count rose " + fmt(rho)
                            + "x with no face-value change. A small bonus issue and a rights issue look "
                            + "identical here, and dividing out the wrong one misstates earnings per share, "
                            + "so this stock is refused rather than guessed.");
                }
            }
            if (Math.abs(rho - 1.0) > tol.nonActionBand()) {
                return refuse(rows, UNEXPLAINED_SHARE_STEP, label + ": the share count moved " + fmt(rho)
                        + "x, beyond the +/-" + Math.round(tol.nonActionBand() * 100) + "% a share issue or "
                        + "buyback explains, and no corporate action matches it.");
            }
            // Within the band and not grid-like: a real issue or buyback. It stays in the series.
        }

        List<Point> points = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            double factor = 1.0;
            for (int i = j + 1; i < n; i++) factor *= ratio[i];
            AnnualFundamentalsEntity r = rows.get(j);
            points.add(new Point(r.getFiscalYear(), r.getShareCount(), r.getFaceValue(), factor));
        }
        return new Result(points, actions, null, null);
    }

    private static Result refuse(List<AnnualFundamentalsEntity> rows, String code, String why) {
        List<Point> points = new ArrayList<>();
        for (AnnualFundamentalsEntity r : rows) {
            points.add(new Point(r.getFiscalYear(), r.getShareCount(), r.getFaceValue(), 1.0));
        }
        return new Result(points, List.of(), code, why);
    }

    /** Face-value split ratio when both values are present, positive and differ; else null. */
    private static Double faceValueAction(Double prev, Double now) {
        if (prev == null || now == null || prev <= 0 || now <= 0) return null;
        if (Math.abs(prev - now) <= 1e-9) return null;
        return prev / now;
    }

    /** A face value that changes and then comes back to an earlier value is a tagging error. */
    private static String faceValueReturn(List<AnnualFundamentalsEntity> rows) {
        List<Double> seen = new ArrayList<>();
        Double last = null;
        for (AnnualFundamentalsEntity r : rows) {
            Double fv = r.getFaceValue();
            if (fv == null) continue;
            if (last != null && Math.abs(fv - last) > 1e-9) {
                for (Double earlier : seen) {
                    if (Math.abs(earlier - fv) <= 1e-9) {
                        return "Face value moved to " + fmt(fv) + " in FY" + r.getFiscalYear()
                                + ", which it already was earlier in the record - a face value cannot go back, "
                                + "so one of these years is mis-tagged.";
                    }
                }
            }
            if (!seen.contains(fv)) seen.add(fv);
            last = fv;
        }
        return null;
    }

    private static Double nearestGrid(double rho, double tolerance) {
        for (double g : GRID) {
            if (Math.abs(rho - g) / g <= tolerance) return g;
        }
        return null;
    }

    private static Double nearestInverseGrid(double rho, double tolerance) {
        for (double g : GRID) {
            double inv = 1.0 / g;
            if (Math.abs(rho - inv) / inv <= tolerance) return inv;
        }
        return null;
    }

    private static String fmt(Double v) {
        if (v == null) return "?";
        return v == Math.rint(v) ? String.valueOf((long) (double) v) : String.format("%.4g", v);
    }

    private static String fmt(double v) {
        return fmt(Double.valueOf(v));
    }
}
