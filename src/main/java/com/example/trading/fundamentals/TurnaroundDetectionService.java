package com.example.trading.fundamentals;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects companies recovering from a bad stretch (SPEC.md §32.3).
 *
 * <p><b>Why this needs a decade and the rest of the system does not.</b> Every other
 * scoring input here reads the present: this quarter's growth, this year's ROCE, today's
 * momentum. A turnaround is defined by its shape over years — debt coming down run after
 * run, interest cost shrinking in absolute rupees, margins inflecting up off a multi-year
 * floor. Five quarters of integrated-filing data cannot see any of that, which is why
 * §32's history table exists.
 *
 * <p><b>Why ≥3 of 4 rather than all 4.</b> Real recoveries are ragged: a company can
 * deleverage hard while margins lag a year, or inflect margins first and repay later.
 * Requiring all four would find almost nothing; requiring one would fire on noise.
 *
 * <p><b>What this is not.</b> The criteria say the numbers have turned. They say nothing
 * about why, whether it holds, or whether the market has already paid for it. It is a
 * prompt to read the filings, never a buy signal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TurnaroundDetectionService {

    private final FundamentalsHistoryService historyService;

    /** Criteria that must be met before the flag is raised. */
    public static final int CRITERIA_REQUIRED = 3;

    /** Absolute debt-to-equity ceiling. Falling debt on a still-crushing balance sheet is not a turnaround yet. */
    public static final double MAX_DEBT_TO_EQUITY = 1.0;

    /** Margin must inflect at least this far above its own recent average (percentage points). */
    public static final double MARGIN_INFLECTION_PP = 2.0;

    /** Sales CAGR floor for the operating-leverage criterion. */
    public static final double MIN_SALES_CAGR_PERCENT = 8.0;

    @Data
    @Builder
    public static class TurnaroundResult {
        private String symbol;
        /** TURNAROUND_CANDIDATE / NOT_TURNING / INSUFFICIENT_HISTORY */
        private String verdict;
        private int criteriaMet;
        private int yearsAvailable;
        /** Criteria that passed, in plain English — the reason to look, stated as evidence. */
        private List<String> signals;
        /** Criteria that were checked and failed, so the reader sees the whole picture. */
        private List<String> notMet;
        /** Criteria that could not be evaluated because the data was missing. */
        private List<String> notMeasured;

        public boolean isCandidate() {
            return "TURNAROUND_CANDIDATE".equals(verdict);
        }
    }

    public TurnaroundResult detect(String symbol) {
        return classify(symbol, historyService.history(symbol));
    }

    /**
     * Pure classifier — no repository, so the criteria boundaries are testable directly.
     *
     * @param history rows oldest-first, as {@link AnnualFundamentalsRepository#findHistory} returns them
     */
    public static TurnaroundResult classify(String symbol, List<AnnualFundamentalsEntity> history) {
        List<String> signals = new ArrayList<>();
        List<String> notMet = new ArrayList<>();
        List<String> notMeasured = new ArrayList<>();

        int years = history == null ? 0 : history.size();
        if (years < FundamentalsHistoryService.MIN_YEARS_FOR_ANALYSIS) {
            return TurnaroundResult.builder()
                    .symbol(symbol)
                    .verdict("INSUFFICIENT_HISTORY")
                    .criteriaMet(0)
                    .yearsAvailable(years)
                    .signals(signals)
                    .notMet(notMet)
                    .notMeasured(List.of("Needs at least " + FundamentalsHistoryService.MIN_YEARS_FOR_ANALYSIS
                            + " years of history; have " + years))
                    .build();
        }

        checkDeleveraging(history, signals, notMet, notMeasured);
        checkInterestFalling(history, signals, notMet, notMeasured);
        checkMarginInflection(history, signals, notMet, notMeasured);
        checkOperatingLeverage(history, signals, notMet, notMeasured);

        int met = signals.size();
        return TurnaroundResult.builder()
                .symbol(symbol)
                .verdict(met >= CRITERIA_REQUIRED ? "TURNAROUND_CANDIDATE" : "NOT_TURNING")
                .criteriaMet(met)
                .yearsAvailable(years)
                .signals(signals)
                .notMet(notMet)
                .notMeasured(notMeasured)
                .build();
    }

    // ---- individual criteria

    /** Debt-to-equity falling three years running AND now below 1. */
    private static void checkDeleveraging(List<AnnualFundamentalsEntity> h,
                                          List<String> signals, List<String> notMet, List<String> notMeasured) {
        List<Double> de = lastN(h, 4, AnnualFundamentalsEntity::debtToEquity);
        if (de.size() < 4) {
            notMeasured.add("Debt trend — needs four years of borrowings and equity");
            return;
        }
        boolean falling = de.get(1) < de.get(0) && de.get(2) < de.get(1) && de.get(3) < de.get(2);
        double latest = de.get(3);
        if (falling && latest < MAX_DEBT_TO_EQUITY) {
            signals.add(String.format(
                    "Debt has fallen three years running and now stands at %.2fx equity — the balance sheet is being repaired",
                    latest));
        } else if (falling) {
            notMet.add(String.format(
                    "Debt is falling but still high at %.2fx equity — repair is under way, not finished", latest));
        } else {
            notMet.add("Debt has not fallen for three consecutive years");
        }
    }

    /** Interest cost falling in absolute rupees — the cash proof that debt repayment is real. */
    private static void checkInterestFalling(List<AnnualFundamentalsEntity> h,
                                             List<String> signals, List<String> notMet, List<String> notMeasured) {
        List<Double> interest = lastN(h, 3, AnnualFundamentalsEntity::getInterestCost);
        if (interest.size() < 3) {
            notMeasured.add("Interest trend — needs three years of interest cost");
            return;
        }
        if (interest.get(2) < interest.get(1) && interest.get(1) < interest.get(0)) {
            signals.add(String.format(
                    "Interest bill has shrunk two years running, from Rs %.0f cr to Rs %.0f cr — real money freed up each year",
                    interest.get(0), interest.get(2)));
        } else {
            notMet.add("Interest cost is not consistently falling");
        }
    }

    /** Operating margin inflecting up off its own multi-year average. */
    private static void checkMarginInflection(List<AnnualFundamentalsEntity> h,
                                              List<String> signals, List<String> notMet, List<String> notMeasured) {
        List<Double> margins = lastN(h, 4, AnnualFundamentalsEntity::operatingMarginPercent);
        if (margins.size() < 4) {
            notMeasured.add("Margin trend — needs four years of sales and operating profit");
            return;
        }
        double latest = margins.get(3);
        double priorAvg = (margins.get(0) + margins.get(1) + margins.get(2)) / 3.0;
        if (latest > priorAvg + MARGIN_INFLECTION_PP) {
            signals.add(String.format(
                    "Operating margin has turned up to %.1f%% from a three-year average of %.1f%% — the business is earning more on each rupee of sales",
                    latest, priorAvg));
        } else {
            notMet.add(String.format("Operating margin %.1f%% is not meaningfully above its %.1f%% three-year average",
                    latest, priorAvg));
        }
    }

    /** Sales growing AND profit growing faster — operating leverage, the engine of a re-rating. */
    private static void checkOperatingLeverage(List<AnnualFundamentalsEntity> h,
                                               List<String> signals, List<String> notMet, List<String> notMeasured) {
        List<Double> sales = lastN(h, 4, AnnualFundamentalsEntity::getSales);
        List<Double> profit = lastN(h, 4, AnnualFundamentalsEntity::getNetProfit);
        if (sales.size() < 4 || profit.size() < 4) {
            notMeasured.add("Operating leverage — needs four years of sales and net profit");
            return;
        }
        Double salesCagr = cagr(sales.get(0), sales.get(3), 3);
        Double profitCagr = cagr(profit.get(0), profit.get(3), 3);
        if (salesCagr == null || profitCagr == null) {
            // Loss-making or zero base years make a CAGR meaningless rather than merely
            // unflattering — say so instead of emitting a number nobody can interpret.
            notMeasured.add("Operating leverage — growth rates are not computable from a zero or negative base");
            return;
        }
        if (salesCagr > MIN_SALES_CAGR_PERCENT && profitCagr > salesCagr) {
            signals.add(String.format(
                    "Sales growing %.0f%% a year with profit growing faster at %.0f%% — costs are being spread over a bigger business",
                    salesCagr, profitCagr));
        } else {
            notMet.add(String.format("Sales growth %.0f%%/yr and profit growth %.0f%%/yr do not show operating leverage",
                    salesCagr, profitCagr));
        }
    }

    // ---- helpers

    /**
     * The last {@code n} values of a field, oldest-first. Returns an empty list unless all
     * {@code n} are present: a gap in the middle of a trend makes the trend unmeasurable,
     * and silently closing the gap would invent a trajectory.
     */
    private static List<Double> lastN(List<AnnualFundamentalsEntity> h, int n,
                                      java.util.function.Function<AnnualFundamentalsEntity, Double> get) {
        if (h.size() < n) return List.of();
        List<Double> out = new ArrayList<>(n);
        for (AnnualFundamentalsEntity e : h.subList(h.size() - n, h.size())) {
            Double v = get.apply(e);
            if (v == null) return List.of();
            out.add(v);
        }
        return out;
    }

    /** Compound annual growth rate in %, or null when the base is not positive. */
    static Double cagr(Double first, Double last, int years) {
        if (first == null || last == null || first <= 0 || last <= 0 || years <= 0) return null;
        return (Math.pow(last / first, 1.0 / years) - 1.0) * 100.0;
    }
}
