package com.example.trading.valuation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Rule-set VG-2: every constant the valuation-gap lens uses, in one place (SPEC §57). Pure.
 *
 * <p>Constants live in Java, not {@code application.yml}, on purpose (the {@code GoalMix}
 * precedent): a threshold that can be edited in a config file can drift away from the stored
 * record that was written under it. A change here fails {@code ValuationGapRulesTest}, which forces
 * a version bump, and old snapshot rows are never rewritten - new rule-set rows append beside them.
 *
 * <p>{@link #fingerprint()} hashes every constant below <b>including the validation rule VG-V1 and
 * the reference-multiple set</b>, and is deliberately over-sensitive (SPEC §38.1): splitting two
 * identical runs is recoverable, merging two different methods is not.
 *
 * <p>Every number tagged [P] is <b>provisional</b>: a starting value awaiting a calibration report,
 * not a finding. None is presented on screen as proof that a trend predicts anything.
 */
public final class ValuationGapRules {

    private ValuationGapRules() {
    }

    public static final String RULESET = "VG-2";

    // ---- Hurdle (restated from SPEC §12.5; pinned to IntrinsicValuationService by reflection in a test)
    /** The yearly return a price must have earned. Price return only. */
    public static final double HURDLE_RATE = 0.12;
    /** The same ±200 bps band §12.5 uses for its sensitivity. */
    public static final double[] SENSITIVITY_RATES = {0.10, 0.14};

    // ---- The record (VG-R1) - observations
    public static final int MIN_OBSERVATIONS = 5;
    public static final int MAX_OBSERVATIONS = 10;

    // ---- Normalisation (VG-N1) [P]
    /** Latest EPS is lowered to the fitted value only when it exceeds the fit by this factor. */
    public static final double PEAK_FACTOR = 1.5;

    // ---- Trend reliability gates (VG-T1) [P]
    /** Standard error of the log-linear slope, in percentage points of annual log-growth. */
    public static final double MAX_STD_ERR_PP = 5.0;
    public static final double MIN_R_SQUARED = 0.5;

    // ---- Data-quality gates [P]
    public static final double CROSSCHECK_TOLERANCE = 0.05;
    public static final double SHARE_BASIS_TOLERANCE = 0.02;
    public static final double NON_ACTION_BAND = 0.25;
    public static final int MAX_PRICE_AGE_DAYS = 14;
    public static final double COMPLETE_DATE_SHARE = 0.90;
    /** A bonus or split must clear this ratio to be believed without a face-value change. */
    public static final double MIN_BONUS_RATIO = 1.5;
    /** Grid tolerance for the ratios above; the older 0.05% rule rejected real bonuses (round 1). */
    public static final double RATIO_GRID_TOLERANCE = 0.01;
    /** A grid-like share step at or under this, with no face-value change, is ambiguous: refuse. */
    public static final double AMBIGUOUS_CEILING = 1.25;
    /** A snapshot is skipped when no complete screening date is this recent. */
    public static final int MAX_CAPTURE_DATE_AGE_DAYS = 5;

    /**
     * Reference multiples for the sensitivity grid <b>only</b> (decision 6, SPEC §57.5).
     *
     * <p>Provisional, version-controlled, never a valuation assumption. Package-private on purpose:
     * a source-scan test fails if any class other than the grid and this one refers to it, so the
     * numbers cannot drift into a range, a verdict or a price. See {@link #MULTIPLES_LIMITS}.
     */
    static final int[] GRID_REFERENCE_MULTIPLES = {13, 25, 35};

    /** Printed beside the grid, always. */
    public static final String MULTIPLES_LIMITS =
            "Reference multiples for this table only - a what-if column, not a view. 13x is the "
                    + "multiple of profit-plus-depreciation that the 12% reverse-DCF's terminal "
                    + "assumptions imply at year 10, a different quantity from earnings per share "
                    + "at year 4-5, so treat it as a low reference column and nothing more. 25x and "
                    + "35x are roughly the lower-quartile and median price-to-earnings of the "
                    + "stocks this lens can read today, rounded: one date's cross-section in a strong "
                    + "market, the same for every business, and they will drift. They describe what "
                    + "is typical now. They are not what any business deserves and not what any market "
                    + "will pay in 2031.";

    /** The validation pre-registration, recorded verbatim so the fingerprint covers it. */
    public static final String VALIDATION_RULE =
            "VG-V1: for every stored row with fit parameters, graded value for graded year Y = "
                    + "normalised_eps x (1 + c)^(Y - base FY), c = exp(slope) - 1 from the stored slope; "
                    + "score = ln(actual adjusted EPS in Y / graded value); a non-positive actual is "
                    + "counted as ACTUAL_NON_POSITIVE, never dropped; unit = (symbol, base FY, graded "
                    + "year, ruleset) using the earliest row written; the actual is the first-seen value "
                    + "frozen in the next input-change row; an actual with no available-from date is "
                    + "unmatured, not graded.";

    /** One line of the printed rule table. */
    public record Rule(String id, String rule, String selects) {
    }

    /** The table printed on screen and served by {@code GET /rules}. */
    public static List<Rule> rules() {
        return List.of(
                new Rule("VG-S1", "Share-count steps: a face-value change is a split; otherwise a ratio of "
                        + MIN_BONUS_RATIO + " or more within " + pct(RATIO_GRID_TOLERANCE) + " of a simple "
                        + "bonus ratio is divided out; a grid-like step of " + AMBIGUOUS_CEILING + " or less "
                        + "with no face-value change is ambiguous and the stock is refused; other steps "
                        + "within +/-" + pct(NON_ACTION_BAND) + " are real share issues and stay in.",
                        "adjusted earnings per share"),
                new Rule("VG-N1", "Normalised earnings = the latest year's earnings per share, lowered to the "
                        + "fitted value only if it is more than " + PEAK_FACTOR + "x the fit (a clear peak). "
                        + "A trough is never lifted.", "E0"),
                new Rule("VG-R1", "The record = a log-linear fit over every contiguous year on one reporting "
                        + "basis, " + MIN_OBSERVATIONS + " to " + MAX_OBSERVATIONS + " years, with its "
                        + "standard error, observation count and R-squared.", "trend growth"),
                new Rule("VG-T1", "The FY2031 historical-trend comparison is shown only if the slope's "
                        + "standard error is at most " + MAX_STD_ERR_PP + " percentage points and R-squared "
                        + "is at least " + MIN_R_SQUARED + ". These are provisional reliability gates, "
                        + "not proof that a past trend carries on.", "block D"),
                new Rule("VG-M1", "Grid reference multiples are the sensitivity-grid columns only. "
                        + MULTIPLES_LIMITS, "block E columns"),
                new Rule("VG-H1", "Hurdle: " + pct(HURDLE_RATE) + " a year, price return only; sensitivity at "
                        + pct(SENSITIVITY_RATES[0]) + " and " + pct(SENSITIVITY_RATES[1]) + ".", "hurdle"),
                new Rule("VG-H2", "Growth years n = 2031 - latest fiscal year; T = years from the screening "
                        + "price date to 31 March 2031.", "horizon"),
                new Rule("VG-V1", VALIDATION_RULE, "snapshot grading"));
    }

    private static String pct(double v) {
        double p = v * 100.0;
        return (p == Math.rint(p) ? String.valueOf((long) p) : String.valueOf(p)) + "%";
    }

    /** SHA-256 over every constant, the multiples and the validation rule. 64 hex characters. */
    public static String fingerprint() {
        StringBuilder sb = new StringBuilder(RULESET);
        sb.append('|').append(HURDLE_RATE)
                .append('|').append(SENSITIVITY_RATES[0]).append(',').append(SENSITIVITY_RATES[1])
                .append('|').append(MIN_OBSERVATIONS).append(',').append(MAX_OBSERVATIONS)
                .append('|').append(PEAK_FACTOR)
                .append('|').append(MAX_STD_ERR_PP).append(',').append(MIN_R_SQUARED)
                .append('|').append(CROSSCHECK_TOLERANCE).append(',').append(SHARE_BASIS_TOLERANCE)
                .append(',').append(NON_ACTION_BAND).append(',').append(MAX_PRICE_AGE_DAYS)
                .append(',').append(COMPLETE_DATE_SHARE).append(',').append(MIN_BONUS_RATIO)
                .append(',').append(RATIO_GRID_TOLERANCE).append(',').append(AMBIGUOUS_CEILING)
                .append(',').append(MAX_CAPTURE_DATE_AGE_DAYS)
                .append('|').append(FiscalHorizon.REFERENCE_FISCAL_YEAR).append(',')
                .append(FiscalHorizon.MIN_RUNWAY_DAYS);
        sb.append('|');
        for (int m : GRID_REFERENCE_MULTIPLES) sb.append(m).append(',');
        sb.append('|').append(VALIDATION_RULE);
        return sha256(sb.toString());
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JVM", e);
        }
    }
}
