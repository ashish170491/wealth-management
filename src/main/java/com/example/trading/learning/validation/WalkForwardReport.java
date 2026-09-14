package com.example.trading.learning.validation;

import java.time.LocalDate;
import java.util.List;

/**
 * The result of one walk-forward evaluation of the pre-registered weight variants (SPEC §38.7).
 *
 * <p>Read {@link #independentPeriods()} before anything else in this record. It is the number
 * that decides whether the rest of it means anything, and it is deliberately the first field.
 * The screening table holds thousands of rows, but those rows are a few hundred stocks moving
 * together, re-measured every few days, over overlapping return windows — so the honest sample
 * size is the number of non-overlapping periods, which is currently around five. SPEC §25.5
 * restates the point after it was nearly missed: row count is not sample size.
 *
 * @param independentPeriods non-overlapping blocks that produced a usable cross-section — the
 *                           real sample size, and the denominator of every t-statistic here
 * @param blocksTotal        blocks the date range spans, before the embargo dropped any
 * @param embargoApplied     whether alternate blocks were dropped so no return window of one
 *                           kept block reaches into the next
 * @param datesEvaluated     screening dates that produced a cross-sectional reading
 * @param datesNotMatured    dates too recent for the horizon to have elapsed — not a failure
 * @param datesTooSmall      dates with too few measurable stocks to correlate across
 * @param rowsExcludedInexact rows dropped because the variant reconstruction could not be exact
 *                           (a composite at a clamp boundary or carrying the hard cap). Dropped
 *                           from every variant alike so the cross-sections stay comparable
 * @param scoringVersions    provenance stamps present in the sample. More than one means the
 *                           sample pools rows from different engines, and B-018 and B-019 are
 *                           what that looks like when nobody notices (SPEC §38.1)
 */
public record WalkForwardReport(int independentPeriods,
                                int blocksTotal,
                                boolean embargoApplied,
                                int horizonDays,
                                LocalDate firstDate,
                                LocalDate lastDate,
                                int datesEvaluated,
                                int datesNotMatured,
                                int datesTooSmall,
                                int rowsExcludedInexact,
                                List<String> scoringVersions,
                                List<VariantResult> variants,
                                String verdict) {

    /**
     * One variant's out-of-sample record.
     *
     * <p>Every variant here is evaluated purely out of sample, because none of them was fitted:
     * they were declared in advance in {@code WeightVariantRegistry}. That is what makes a plain
     * block average legitimate. The moment one is <i>chosen</i> on these numbers, the choosing
     * is the fitting — which is why {@link PromotionGate} charges for the number of variants
     * before letting any of them be adopted.
     *
     * @param meanIc          mean cross-sectional rank correlation across blocks, or null when
     *                        no block produced one
     * @param stdDevIc        spread of the per-block readings; null below two blocks
     * @param tStatistic      mean over standard error, against a null of no predictive power;
     *                        null when it cannot be computed. Not a p-value — the gate converts
     *                        it, after deflating for how many variants were tried
     * @param meanIcVsLive    mean of (this variant's block IC − the live vector's block IC).
     *                        The paired difference is the powerful comparison: both variants see
     *                        the same stocks on the same dates, so the market move common to
     *                        both cancels instead of drowning the difference
     * @param tStatisticVsLive t-statistic of that paired difference; null for the live vector
     * @param blockIcs        the per-block readings themselves, so a mean resting on one good
     *                        block is visible rather than inferred
     * @param meanCoverage    average share of the cross-section this variant could score at all.
     *                        A variant that ranks only the stocks with full fundamental coverage
     *                        is not competing on the same universe, and its IC must be read with
     *                        that in mind rather than compared naively
     */
    public record VariantResult(String name,
                                String hypothesis,
                                Double meanIc,
                                Double stdDevIc,
                                Double tStatistic,
                                Double meanIcVsLive,
                                Double tStatisticVsLive,
                                List<Double> blockIcs,
                                Double meanCoverage,
                                int observations) {
    }
}
