package com.example.trading.valuation;

import java.util.ArrayList;
import java.util.List;

/**
 * Blocks D, E and F of the valuation-gap lens (SPEC §57.2): the historical-trend comparison, the
 * required-growth sensitivity grid, and the restatement of the existing reverse-DCF reading. Pure.
 *
 * <p><b>Three things, deliberately separate.</b>
 * <ul>
 *   <li><b>D, historical-trend comparison</b> - what earnings per share would be in FY2031 <i>if the
 *       record's own trend simply carried on</i>, shown only when both reliability gates pass, computed
 *       on read, never stored. It says nothing about what will happen.</li>
 *   <li><b>E, required-growth grid</b> - for each reference multiple, the yearly EPS growth to FY2031
 *       that today's price would need for it to have earned the hurdle rate as <i>price return only</i>.
 *       A closed form with no terminal growth and no dividends.</li>
 *   <li><b>F, existing reverse-DCF</b> - the stored implied growth, exactly as stored and never
 *       recomputed.</li>
 * </ul>
 * No figure from one is an input to another and no product of D and E is ever printed or returned: a
 * reader can multiply two numbers, and the vocabulary and source-scan tests exist so this code never
 * does it for them. The reference multiples are package-private to
 * {@link ValuationGapRules}; this class is the only other one that may read them.
 */
public final class RequiredGrowthGrid {

    private RequiredGrowthGrid() {
    }

    public static final String TREND_LABEL = "Historical-trend comparison";

    public static final String TREND_STATEMENT =
            "This compares today's price with where the past earnings trend would lead if it simply "
                    + "carried on. It says nothing about what will actually happen.";

    public static final String DIFFERENCE_BETWEEN_METHODS =
            "These two cards ask different questions and are built differently. The grid fixes a "
                    + "multiple for 2031 and asks what growth the price needs; the reverse-DCF fixes a "
                    + "10-year growth path with a terminal value and asks what growth today's price implies. "
                    + "Both start from today's price, so they usually move together (across the stocks this "
                    + "lens can read the two correlate at about 0.8) and for some stocks they land close "
                    + "together by accident. Where they differ the difference is real. Neither confirms the "
                    + "other, and they are never blended or averaged.";

    public static final String SECTION_125_POINTER = "Is the price sensible?";

    /** One cell of the grid at a different hurdle rate. */
    public record RateRow(double ratePct, Double requiredGrowthPct) {
    }

    /** One reference multiple. {@code gapToTrendPp} is absent when the trend's reliability gates fail. */
    public record GridRow(int referenceMultiple, Double requiredGrowthPct, Double gapToTrendPp, String note,
                          List<RateRow> atDiscount) {
    }

    public record Grid(ValuationReading.Methodology methodology, List<GridRow> rows, String limits) {
    }

    public static ValuationReading.Methodology gridMethodology() {
        return new ValuationReading.Methodology("Required-growth grid", List.of(
                "stated reference multiple",
                "reference year FY" + FiscalHorizon.REFERENCE_FISCAL_YEAR,
                "price-return hurdle of " + Math.round(ValuationGapRules.HURDLE_RATE * 100) + "% a year",
                "earnings per share",
                "closed form, no terminal growth, dividends ignored"));
    }

    public static ValuationReading.Methodology section125Methodology() {
        return new ValuationReading.Methodology("Existing reverse-DCF reading", List.of(
                "10-year constant growth, then 4% terminal",
                "12% discount rate",
                "profit-plus-depreciation proxy",
                "solved by the existing valuation service, as stored"));
    }

    /**
     * Block E.
     *
     * @param price            the screening price, rupees
     * @param normalisedEps    {@code E0}
     * @param growthYears      {@code n}, whole fiscal years from the base year to FY2031
     * @param yearsToHorizon   {@code T}, years from the price date to 31 March 2031
     * @param trendGrowthPct   the record's trend growth, or null when its reliability gates fail
     */
    public static Grid grid(double price, double normalisedEps, int growthYears, double yearsToHorizon,
                            Double trendGrowthPct) {
        List<GridRow> rows = new ArrayList<>();
        for (int multiple : ValuationGapRules.GRID_REFERENCE_MULTIPLES) {
            double g = requiredGrowth(price, normalisedEps, growthYears, yearsToHorizon,
                    ValuationGapRules.HURDLE_RATE, multiple);
            List<RateRow> sensitivity = new ArrayList<>();
            for (double r : ValuationGapRules.SENSITIVITY_RATES) {
                sensitivity.add(new RateRow(Math.round(r * 10000) / 100.0,
                        requiredGrowth(price, normalisedEps, growthYears, yearsToHorizon, r, multiple) * 100.0));
            }
            Double gap = trendGrowthPct == null ? null : g * 100.0 - trendGrowthPct;
            String note = g < 0
                    ? "Earnings per share could shrink every year and the price would still have earned "
                    + Math.round(ValuationGapRules.HURDLE_RATE * 100) + "% a year at this multiple."
                    : null;
            rows.add(new GridRow(multiple, g * 100.0, gap, note, sensitivity));
        }
        return new Grid(gridMethodology(), rows, ValuationGapRules.MULTIPLES_LIMITS);
    }

    /**
     * {@code g_req = (P (1 + r)^T / (N E0))^(1/n) - 1}: the yearly growth in earnings per share, over
     * the {@code n} fiscal years to the reference year, for a price of {@code P} to have compounded at
     * {@code r} for {@code T} years if the market then pays {@code N} times that year's earnings.
     */
    public static double requiredGrowth(double price, double normalisedEps, int growthYears,
                                        double yearsToHorizon, double hurdleRate, double multiple) {
        double endValue = price * Math.pow(1.0 + hurdleRate, yearsToHorizon);
        return Math.pow(endValue / (multiple * normalisedEps), 1.0 / growthYears) - 1.0;
    }

    /** Block D. Withheld, with the failing figure quoted, when either reliability gate fails. */
    public static ValuationReading.TrendComparison trendComparison(double price, NormalisedEarnings.Fit fit) {
        double pe0 = price / fit.normalisedEps();
        if (fit.reliable()) {
            int n = FiscalHorizon.growthYears(fit.lastFiscalYear());
            double eps = fit.normalisedEps() * Math.pow(1.0 + fit.trendGrowth(), n);
            return new ValuationReading.TrendComparison(TREND_LABEL, TREND_STATEMENT, pe0, eps,
                    FiscalHorizon.REFERENCE_FISCAL_YEAR, null);
        }
        List<String> failing = new ArrayList<>();
        if (!fit.stdErrGatePasses()) {
            failing.add(String.format("the standard error of the trend is %.1f percentage points (the limit is %.1f)",
                    fit.stdErrPp(), ValuationGapRules.MAX_STD_ERR_PP));
        }
        if (!fit.rSquaredGatePasses()) {
            failing.add(fit.rSquared() == null
                    ? "the earnings are identical every year, so there is no trend to read"
                    : String.format("R-squared is %.2f (at least %.1f is needed)", fit.rSquared(),
                    ValuationGapRules.MIN_R_SQUARED));
        }
        return new ValuationReading.TrendComparison(TREND_LABEL, TREND_STATEMENT, pe0, null, null,
                "The past trend is too uncertain to be set beside today's price: "
                        + String.join(" and ", failing) + ".");
    }

    /** Card F, from the stored columns only. {@code impliedGrowthPct} null makes the card absent. */
    public static ValuationReading.Section125 section125(Double impliedGrowthPct) {
        if (impliedGrowthPct == null) return null;
        return new ValuationReading.Section125(section125Methodology(), impliedGrowthPct,
                "The reverse-DCF grows profit-plus-depreciation over 10 years with a 4% terminal value and "
                        + "is known to under-value long-duration compounders (IT services, platforms, "
                        + "pharma). The figure is shown as stored; it is never recomputed here.",
                SECTION_125_POINTER);
    }
}
