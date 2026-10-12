package com.example.trading.ai;

import com.example.trading.holdings.StockValuationService;
import com.example.trading.holdings.StockValuationService.ValuationData;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reverse-DCF sanity check. Instead of projecting fair value from assumed
 * growth (noisy), this service takes the current market cap as given and
 * solves for the growth rate that would justify today's price. The output is
 * compared against the company's historical profit CAGR to flag stocks whose
 * market price has detached from what the business has actually demonstrated.
 *
 * Model parameters (fixed — see SPEC.md §12.5 caveat block for rationale):
 *   • discount rate (WACC)   = 12%  — Indian equity cost-of-capital baseline
 *   • terminal growth        =  4%  — long-run India nominal GDP floor
 *   • forecast window        = 10 years
 *   • FCF proxy              = net profit + depreciation (annualized from latest quarter × 4)
 *
 * Caveats:
 *   • The 10-year window + 4% terminal growth systematically under-values
 *     long-duration compounders (IT services, platforms, pharma). The verdict
 *     may flag these as EXPENSIVE when they are arguably fair — use as a sanity
 *     check, not a price target.
 *   • Banks, insurers, and commodity cyclicals need residual-income or
 *     through-cycle models; this service returns {@code NOT_APPLICABLE} for
 *     loss-making inputs, but does not otherwise distinguish sectors.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IntrinsicValuationService {

    private static final double DISCOUNT_RATE = 0.12;
    private static final double TERMINAL_GROWTH = 0.04;
    private static final int FORECAST_YEARS = 10;
    private static final int MAX_BISECTION_ITERS = 60;
    private static final double BISECTION_TOL = 1.0; // rupees

    private final StockValuationService valuationService;
    private final NseDataService nseDataService;
    private final com.example.trading.fundamentals.FundamentalsHistoryService fundamentalsHistoryService;

    public ReverseDcfResult analyze(String symbol) {
        String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
        ReverseDcfResult r = new ReverseDcfResult();
        r.setSymbol(symbol);

        // Market cap — required
        ValuationData val;
        try {
            val = valuationService.getValuationData(symbol);
        } catch (Exception e) {
            r.setVerdict("INSUFFICIENT_DATA");
            r.setCaveat("Valuation data unavailable: " + e.getMessage());
            return r;
        }
        if (val == null || val.getMarketCap() == null || val.getMarketCap() <= 0) {
            r.setVerdict("INSUFFICIENT_DATA");
            r.setCaveat("Market cap unavailable");
            return r;
        }
        double marketCapCr = val.getMarketCap();

        // Latest quarterly profit + depreciation → annualize × 4
        List<NseDataService.QuarterlyResult> quarters = nseDataService.fetchQuarterlyResults(tradingSymbol);
        if (quarters == null || quarters.isEmpty()) {
            r.setVerdict("INSUFFICIENT_DATA");
            r.setCaveat("Quarterly data unavailable");
            return r;
        }
        NseDataService.QuarterlyResult latest = quarters.get(0);
        Double profit = latest.getProfit();
        Double depreciation = latest.getDepreciation();
        if (profit == null) {
            r.setVerdict("INSUFFICIENT_DATA");
            r.setCaveat("Latest-quarter profit unavailable");
            return r;
        }
        if (profit <= 0) {
            r.setVerdict("NOT_APPLICABLE");
            r.setCaveat("Company is loss-making — DCF does not apply; use residual-income or through-cycle model");
            r.setLatestAnnualisedProfitCr(profit * 4);
            return r;
        }

        double annualisedProfit = profit * 4;                       // ₹ cr
        double annualisedFcfProxy = depreciation != null
                ? (profit + depreciation) * 4
                : annualisedProfit;                                  // fall back to net profit if no depreciation
        r.setLatestAnnualisedProfitCr(annualisedProfit);
        r.setLatestAnnualisedFcfProxyCr(annualisedFcfProxy);
        r.setMarketCapCr(marketCapCr);
        r.setDiscountRate(DISCOUNT_RATE);
        r.setTerminalGrowth(TERMINAL_GROWTH);
        r.setForecastYears(FORECAST_YEARS);

        // Historical growth reference: profit CAGR over the annual accounts on file, at least four
        // years (B-145). This used to fall back to ONE QUARTER's year-on-year profit change
        // whenever the 8-quarter CAGR was null - which it is for every stock, because NSE's
        // integrated filing has only ~6 quarters - so "historical growth" equalled a single quarter
        // on 272 of 272 rows. A ten-year implied growth was being judged against last quarter's
        // profit swing: "undervalued" meant "profit jumped last quarter" (median +44%) and
        // "extremely expensive" meant "profit fell". Against a >= 4-year CAGR 130 of 223 verdicts
        // change. Same benchmark the analyst-target plausibility panel adopted for the same
        // reason (B-113). Fewer than four years is no record, not a short one.
        Double historicalGrowth = null;
        String noRecordNote = null;
        try {
            java.util.Map<Integer, Double> profitByYear = new java.util.TreeMap<>();
            var rows = fundamentalsHistoryService.history(symbol);
            if ((rows == null || rows.isEmpty()) && !symbol.startsWith("NSE:")) {
                rows = fundamentalsHistoryService.history("NSE:" + tradingSymbol);
            }
            if (rows != null) {
                for (var row : rows) {
                    if (row.getFiscalYear() != null && row.getNetProfit() != null) {
                        profitByYear.put(row.getFiscalYear(), row.getNetProfit());
                    }
                }
            }
            var record = com.example.trading.analyst.AnalystTargetPlausibility.growthRecord(profitByYear);
            if (record.measured()) historicalGrowth = record.cagrPercent() / 100.0;
            else noRecordNote = record.note();
        } catch (Exception e) {
            log.debug("Intrinsic valuation: historical growth unavailable for {}", symbol);
            noRecordNote = "Annual accounts could not be read.";
        }
        r.setHistoricalGrowthPercent(historicalGrowth != null ? historicalGrowth * 100.0 : null);

        // Solve for implied growth via bisection
        Double impliedGrowth = solveImpliedGrowth(annualisedFcfProxy, marketCapCr);
        if (impliedGrowth == null) {
            r.setVerdict("INSUFFICIENT_DATA");
            r.setCaveat("Implied growth could not be solved (extreme inputs)");
            return r;
        }
        r.setImpliedGrowthPercent(impliedGrowth * 100.0);

        // Expectation gap — only when historical is known
        Double expectationGap = null;
        if (historicalGrowth != null) {
            expectationGap = (impliedGrowth - historicalGrowth) * 100.0;
            r.setExpectationGapPercent(expectationGap);
        }

        if (historicalGrowth == null) {
            // No multi-year record to judge a ten-year requirement against: say so, and let the
            // valuation dimension fall back to the P/E half rather than score a guess.
            r.setVerdict("INSUFFICIENT_DATA");
            r.setCaveat(noRecordNote != null ? noRecordNote
                    : "Not enough years of annual accounts to compare against.");
        } else {
            r.setVerdict(classify(impliedGrowth, historicalGrowth));
        }

        // Sensitivity bands — ±200 bps discount rate
        Double impliedLow = solveImpliedGrowth(annualisedFcfProxy, marketCapCr, DISCOUNT_RATE - 0.02);
        Double impliedHigh = solveImpliedGrowth(annualisedFcfProxy, marketCapCr, DISCOUNT_RATE + 0.02);
        if (impliedLow != null) r.setImpliedGrowthAtLowDiscountPercent(impliedLow * 100.0);
        if (impliedHigh != null) r.setImpliedGrowthAtHighDiscountPercent(impliedHigh * 100.0);

        return r;
    }

    private Double solveImpliedGrowth(double fcfProxyCr, double marketCapCr) {
        return solveImpliedGrowth(fcfProxyCr, marketCapCr, DISCOUNT_RATE);
    }

    /**
     * Bisect growth rate in {@code [-0.50, +0.60]} so that {@code fairValue(g) == marketCapCr}.
     * Returns null when the search bounds can't bracket the target (usually extreme inputs).
     */
    private Double solveImpliedGrowth(double fcfProxyCr, double marketCapCr, double discountRate) {
        double lo = -0.50, hi = 0.60;
        double fLo = fairValue(fcfProxyCr, lo, discountRate) - marketCapCr;
        double fHi = fairValue(fcfProxyCr, hi, discountRate) - marketCapCr;
        if (fLo * fHi > 0) return null; // same sign at both bounds → no root in bracket
        for (int i = 0; i < MAX_BISECTION_ITERS; i++) {
            double mid = 0.5 * (lo + hi);
            double fMid = fairValue(fcfProxyCr, mid, discountRate) - marketCapCr;
            if (Math.abs(fMid) < BISECTION_TOL) return mid;
            if (fLo * fMid < 0) { hi = mid; fHi = fMid; } else { lo = mid; fLo = fMid; }
        }
        return 0.5 * (lo + hi);
    }

    /**
     * PV of 10 years of FCF growing at {@code g} plus Gordon terminal value. If
     * the terminal-growth constraint is violated (g' ≥ r), terminal collapses to
     * zero and only the 10-year stream contributes — conservative by design.
     */
    private double fairValue(double fcfProxyCr, double g, double r) {
        double pv = 0;
        double cf = fcfProxyCr;
        for (int y = 1; y <= FORECAST_YEARS; y++) {
            cf = cf * (1 + g);
            pv += cf / Math.pow(1 + r, y);
        }
        double terminalG = Math.min(TERMINAL_GROWTH, g); // can't grow terminal faster than forecast
        if (r > terminalG) {
            double terminalCf = cf * (1 + terminalG);
            double terminalValue = terminalCf / (r - terminalG);
            pv += terminalValue / Math.pow(1 + r, FORECAST_YEARS);
        }
        return pv;
    }

    private String classify(double impliedGrowth, Double historicalGrowth) {
        // Absolute sanity — extreme tails are standalone verdicts
        if (impliedGrowth < -0.05) return "DEEPLY_UNDERVALUED";          // market pricing in decline
        if (impliedGrowth > 0.30) return "EXTREMELY_EXPENSIVE";          // >30% CAGR for 10 years is rare
        // Relative to historical track record
        if (historicalGrowth == null) {
            if (impliedGrowth < 0.05) return "UNDERVALUED";
            if (impliedGrowth < 0.12) return "FAIRLY_VALUED";
            if (impliedGrowth < 0.20) return "EXPENSIVE";
            return "EXTREMELY_EXPENSIVE";
        }
        double gap = impliedGrowth - historicalGrowth;
        if (gap < -0.05) return "UNDERVALUED";
        if (gap < 0.05) return "FAIRLY_VALUED";
        if (gap < 0.12) return "EXPENSIVE";
        return "EXTREMELY_EXPENSIVE";
    }

    @Data
    public static class ReverseDcfResult {
        private String symbol;
        private String verdict;                // DEEPLY_UNDERVALUED / UNDERVALUED / FAIRLY_VALUED / EXPENSIVE / EXTREMELY_EXPENSIVE / NOT_APPLICABLE / INSUFFICIENT_DATA
        private Double impliedGrowthPercent;   // growth rate (% / yr) implied by today's price
        private Double historicalGrowthPercent;// profit CAGR over >= 4 years of annual accounts (% / yr); null below that (B-145)
        private Double expectationGapPercent;  // implied − historical
        private Double impliedGrowthAtLowDiscountPercent;  // sensitivity: discount − 200bps
        private Double impliedGrowthAtHighDiscountPercent; // sensitivity: discount + 200bps
        private Double marketCapCr;
        private Double latestAnnualisedProfitCr;
        private Double latestAnnualisedFcfProxyCr;
        private Double discountRate;
        private Double terminalGrowth;
        private Integer forecastYears;
        private String caveat;
    }
}
