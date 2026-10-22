package com.example.trading.valuation;

import com.example.trading.earnings.FiscalQuarter;
import com.example.trading.earnings.QuarterlyResultEntity;
import com.example.trading.fundamentals.AdjustedShareSeries;
import com.example.trading.fundamentals.AnnualFundamentalsEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adjusted earnings per share by year, and the data-quality gates G3-G9 that decide whether the
 * series may be read at all (SPEC §57.3). Pure: no repository, no clock, no Spring.
 *
 * <p>{@code EPS_t = net_profit_t / (share_count_t x cumulative corporate-action factor)}, i.e. every
 * year restated onto the latest year's share basis with bonus issues and splits divided out.
 *
 * <p><b>One known limit, stated on screen.</b> The stored net profit includes the share of minority
 * interest, so EPS here can differ from reported EPS for a group with large minorities. Owners'
 * profit is deferred (decision 4); gate G9 compares the latest year with the sum of the four
 * reported quarterly EPS figures as the only guard, and earlier years are unverified.
 *
 * <p>Every refusal names the failing number and carries a closed reason code. Nothing here repairs
 * data: a gap is a refusal, never a default (SPEC §21 rule 7).
 */
public final class EpsSeries {

    private EpsSeries() {
    }

    /** One year of the record. {@code eps} is null until the share adjustment has run. */
    public record Year(int fiscalYear, Double netProfitCr, Double shareCountCr, Double faceValue,
                       Boolean consolidated, double shareFactor, Double eps,
                       LocalDate availableFrom, Boolean availableFromIsInferred,
                       String source, LocalDateTime rowUpdatedAt) {
    }

    /** {@code reason} is null when the series passed G3-G8. */
    public record Built(List<Year> years, List<AdjustedShareSeries.Action> actions, Boolean consolidated,
                        ValuationReading.Reason reason, String reasonText) {
        public boolean usable() {
            return reason == null;
        }

        public int observations() {
            return years.size();
        }

        public Year latest() {
            return years.isEmpty() ? null : years.get(years.size() - 1);
        }
    }

    private static final AdjustedShareSeries.Tolerances TOLERANCES = new AdjustedShareSeries.Tolerances(
            ValuationGapRules.MIN_BONUS_RATIO, ValuationGapRules.RATIO_GRID_TOLERANCE,
            ValuationGapRules.AMBIGUOUS_CEILING, ValuationGapRules.NON_ACTION_BAND);

    /**
     * Gates G3-G6 and G8 over one symbol's stored annual rows.
     *
     * @param history        the rows of ONE spelling, any order
     * @param requiredLastFy the oldest latest-year that still counts as current ({@link FiscalHorizon})
     */
    public static Built build(List<AnnualFundamentalsEntity> history, int requiredLastFy) {
        TreeMap<Integer, AnnualFundamentalsEntity> byYear = new TreeMap<>();
        if (history != null) {
            for (AnnualFundamentalsEntity r : history) {
                if (r.getFiscalYear() != null) byYear.put(r.getFiscalYear(), r);
            }
        }
        if (byYear.isEmpty()) {
            return refused(List.of(), ValuationReading.Reason.INSUFFICIENT_HISTORY,
                    "No annual accounts are on file for this company.");
        }

        // The trailing run of consecutive years, newest backwards, capped at the maximum.
        List<AnnualFundamentalsEntity> run = new ArrayList<>();
        Integer expect = null;
        for (Map.Entry<Integer, AnnualFundamentalsEntity> e : byYear.descendingMap().entrySet()) {
            if (expect != null && !e.getKey().equals(expect)) break;
            run.add(0, e.getValue());
            expect = e.getKey() - 1;
            if (run.size() == ValuationGapRules.MAX_OBSERVATIONS) break;
        }
        List<Year> rawRun = toYears(run);
        int lastFy = run.get(run.size() - 1).getFiscalYear();

        if (run.size() < ValuationGapRules.MIN_OBSERVATIONS) {
            return refused(rawRun, ValuationReading.Reason.INSUFFICIENT_HISTORY,
                    "Only " + run.size() + " consecutive fiscal year(s) of accounts end in FY" + lastFy
                            + "; at least " + ValuationGapRules.MIN_OBSERVATIONS
                            + " are needed to see a trend (" + byYear.size() + " year(s) on file in all).");
        }
        if (lastFy < requiredLastFy) {
            return refused(rawRun, ValuationReading.Reason.STALE_LATEST_YEAR,
                    "The newest annual accounts on file are for FY" + lastFy + "; FY" + requiredLastFy
                            + " or later is needed for the record to be current.");
        }

        Boolean latestBasis = run.get(run.size() - 1).getConsolidated();
        if (latestBasis == null) {
            return refused(rawRun, ValuationReading.Reason.BASIS_UNKNOWN,
                    "The newest year (FY" + lastFy + ") does not say whether its figures are consolidated or "
                            + "standalone, so it cannot be safely joined to earlier years.");
        }
        int start = run.size() - 1;
        boolean stoppedOnUnknown = false;
        while (start > 0) {
            Boolean b = run.get(start - 1).getConsolidated();
            if (b == null) {
                stoppedOnUnknown = true;
                break;
            }
            if (!b.equals(latestBasis)) break;
            start--;
        }
        List<AnnualFundamentalsEntity> tail = run.subList(start, run.size());
        if (tail.size() < ValuationGapRules.MIN_OBSERVATIONS) {
            return stoppedOnUnknown
                    ? refused(rawRun, ValuationReading.Reason.BASIS_UNKNOWN,
                    "Only " + tail.size() + " recent year(s) are known to be on the same reporting basis; "
                            + "an earlier year does not say which basis it is on.")
                    : refused(rawRun, ValuationReading.Reason.BASIS_SWITCH,
                    "The reporting basis changes within the record: only " + tail.size()
                            + " recent year(s) are on the " + (latestBasis ? "consolidated" : "standalone")
                            + " basis. Joining the two would invent a collapse or a recovery.");
        }

        for (AnnualFundamentalsEntity r : tail) {
            if (r.getNetProfit() == null || r.getShareCount() == null) {
                return refused(toYears(tail), ValuationReading.Reason.FIELD_MISSING,
                        "FY" + r.getFiscalYear() + " is missing "
                                + (r.getNetProfit() == null ? "net profit" : "the share count") + ".");
            }
        }

        AdjustedShareSeries.Result shares = AdjustedShareSeries.analyse(tail, TOLERANCES);
        if (!shares.usable()) {
            ValuationReading.Reason why = switch (shares.problemCode()) {
                case AdjustedShareSeries.AMBIGUOUS_SHARE_STEP -> ValuationReading.Reason.AMBIGUOUS_SHARE_STEP;
                case AdjustedShareSeries.UNEXPLAINED_SHARE_STEP -> ValuationReading.Reason.UNEXPLAINED_SHARE_STEP;
                default -> ValuationReading.Reason.SHARE_COUNT_INCONSISTENT;
            };
            return refused(toYears(tail), why, shares.problem());
        }

        List<Year> years = new ArrayList<>();
        for (int i = 0; i < tail.size(); i++) {
            AnnualFundamentalsEntity r = tail.get(i);
            double factor = shares.points().get(i).factor();
            double eps = r.getNetProfit() / (r.getShareCount() * factor);
            years.add(year(r, factor, eps));
        }
        for (Year y : years) {
            if (y.eps() <= 0) {
                return new Built(years, shares.actions(), latestBasis, ValuationReading.Reason.LOSS_IN_RECORD,
                        "FY" + y.fiscalYear() + " earnings per share were " + String.format("%.2f", y.eps())
                                + ". A log-linear trend is undefined through a loss, and a stock with a loss in its "
                                + "record is outside what this lens can read.");
            }
        }
        return new Built(years, shares.actions(), latestBasis, null, null);
    }

    private static Built refused(List<Year> years, ValuationReading.Reason reason, String text) {
        return new Built(years, List.of(), null, reason, text);
    }

    private static List<Year> toYears(List<AnnualFundamentalsEntity> rows) {
        List<Year> out = new ArrayList<>();
        for (AnnualFundamentalsEntity r : rows) out.add(year(r, 1.0, null));
        return out;
    }

    private static Year year(AnnualFundamentalsEntity r, double factor, Double eps) {
        return new Year(r.getFiscalYear(), r.getNetProfit(), r.getShareCount(), r.getFaceValue(),
                r.getConsolidated(), factor, eps, r.getAvailableFrom(), r.getAvailableFromEstimated(),
                r.getSource(), r.getUpdatedAt());
    }

    // ------------------------------------------------------------------------------- G7

    /**
     * G7: market capitalisation divided by the price, in crore shares, must match the latest year's
     * share count. A post-year-end bonus or split makes the two disagree and would put today's
     * price on a different share basis from the earnings it is divided by.
     *
     * @return null when they agree, else the relative gap (market-cap shares / year-end shares - 1)
     */
    public static Double shareBasisGap(Double marketCapCr, double price, double latestShareCountCr) {
        if (marketCapCr == null || price <= 0 || latestShareCountCr <= 0) return null;
        double gap = (marketCapCr / price) / latestShareCountCr - 1.0;
        return Math.abs(gap) <= ValuationGapRules.SHARE_BASIS_TOLERANCE ? null : gap;
    }

    // ------------------------------------------------------------------------------- G9

    /**
     * What G9 found. {@code reason} is null when the check passed.
     *
     * @param quarterlyEpsSum the four reported quarterly figures added up, when all four were usable
     * @param ratio           annual EPS divided by that sum
     */
    public record CrossCheck(Double quarterlyEpsSum, Double ratio, boolean passed,
                             ValuationReading.Reason reason, String reasonText) {
    }

    /**
     * G9: the latest year's EPS must agree, within {@link ValuationGapRules#CROSSCHECK_TOLERANCE}, with
     * the sum of that year's four reported quarterly EPS figures on the same reporting basis. It is
     * the only guard against stored net profit that includes minority interest, and it is
     * deliberately conservative - an in-year bonus fails it.
     */
    public static CrossCheck crossCheck(List<QuarterlyResultEntity> quarters, int latestFy,
                                        Boolean annualBasis, double latestEps) {
        Map<Integer, QuarterlyResultEntity> byNumber = new TreeMap<>();
        boolean sawOtherBasis = false;
        if (quarters != null) {
            for (QuarterlyResultEntity q : quarters) {
                if (q.getQuarterEnd() == null || FiscalQuarter.fiscalYear(q.getQuarterEnd()) != latestFy) continue;
                if (q.getConsolidated() == null || !q.getConsolidated().equals(annualBasis)) {
                    sawOtherBasis = true;
                    continue;
                }
                int number = FiscalQuarter.number(q.getQuarterEnd());
                QuarterlyResultEntity existing = byNumber.get(number);
                if (existing == null || newer(q, existing)) byNumber.put(number, q);
            }
        }
        if (byNumber.size() < 4) {
            String why = byNumber.isEmpty() && sawOtherBasis
                    ? "The quarterly results on file for FY" + latestFy + " are on a different (or unstated) "
                    + "reporting basis from the annual figures, so they cannot check them."
                    : "Only " + byNumber.size() + " of the four FY" + latestFy + " quarterly results are on file "
                    + "on the annual figures' reporting basis, so the annual EPS cannot be cross-checked.";
            return new CrossCheck(null, null, false, ValuationReading.Reason.EPS_UNVERIFIABLE, why);
        }
        double sum = 0;
        for (QuarterlyResultEntity q : byNumber.values()) {
            if (q.getEps() == null) {
                return new CrossCheck(null, null, false, ValuationReading.Reason.EPS_UNVERIFIABLE,
                        "A reported quarterly EPS for FY" + latestFy + " is missing, so the annual EPS cannot "
                                + "be cross-checked.");
            }
            sum += q.getEps();
        }
        if (sum <= 0) {
            return new CrossCheck(sum, null, false, ValuationReading.Reason.EPS_CROSSCHECK_FAILED,
                    "The four FY" + latestFy + " quarterly EPS figures total " + String.format("%.2f", sum)
                            + ", which cannot be matched to a positive annual figure.");
        }
        double ratio = latestEps / sum;
        if (Math.abs(ratio - 1.0) > ValuationGapRules.CROSSCHECK_TOLERANCE) {
            return new CrossCheck(sum, ratio, false, ValuationReading.Reason.EPS_CROSSCHECK_FAILED,
                    "Annual EPS of " + String.format("%.2f", latestEps) + " here is " + String.format("%.1f", ratio * 100)
                            + "% of the " + String.format("%.2f", sum) + " the four reported quarters total; "
                            + "the allowed gap is " + Math.round(ValuationGapRules.CROSSCHECK_TOLERANCE * 100) + "%.");
        }
        return new CrossCheck(sum, ratio, true, null, null);
    }

    private static boolean newer(QuarterlyResultEntity a, QuarterlyResultEntity b) {
        Comparator<QuarterlyResultEntity> c = Comparator.comparing(QuarterlyResultEntity::getAvailableFrom,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        return c.compare(a, b) > 0;
    }
}
