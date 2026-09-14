package com.example.trading.fundamentals;

import com.example.trading.ai.NseDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Capex-cycle analysis with the prior year supplied from the annual-history table
 * (SPEC §31 + §32).
 *
 * <p><b>Why this class exists.</b> The plan assumed each Ind-AS filing carries the previous
 * year's balance sheet as a comparative column, making a year-on-year capex delta computable
 * from a single document. Measured against live NSE data on 2026-08-26, that is false: the
 * integrated filing declares a prior instant context and tags exactly <b>one</b> fact against
 * it. No comparative balance sheet exists (B-034).
 *
 * <p>Without a prior year, {@code EXPANSION_UNDERWAY} — the entire point of the feature —
 * could never fire, and capex-to-depreciation could never be computed. F5's
 * {@code annual_fundamentals} table is the fix: it records CWIP and net block per financial
 * year from both the XBRL pipeline and the user's history import, so the second year is
 * available as soon as either source has supplied it. F4 and F5 turn out to be one feature
 * in two commits.
 *
 * <p>Everything degrades honestly: with no prior year the verdict stays at what intensity
 * alone supports, and the reason line says so rather than implying a measurement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapexCycleService {

    private final NseDataService nseDataService;
    private final AnnualFundamentalsRepository fundamentalsRepository;

    /**
     * Analyse a stock's capex cycle, using stored history for the prior year when the
     * filing does not carry it.
     *
     * @param symbol       exchange-qualified symbol, e.g. {@code NSE:RELIANCE}
     * @param industryHint optional industry string for financial-sector detection
     * @return never null; check {@code applicable} before using the figures
     */
    public NseDataService.CapexCycleData analyze(String symbol, String industryHint) {
        String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;

        NseDataService.BalanceSheetData bs = nseDataService.fetchAnnualFinancials(tradingSymbol);
        if (bs == null) {
            NseDataService.CapexCycleData cc = new NseDataService.CapexCycleData();
            cc.setSymbol(symbol);
            cc.setApplicable(false);
            cc.setVerdict("NO_DATA");
            cc.setReason("Annual financial statement unavailable from NSE "
                    + "(no filing, very recent IPO, or unsupported format)");
            return cc;
        }

        boolean isFinancial = bs.isBanking() || (industryHint != null && industryHint.toLowerCase()
                .matches(".*(bank|financ|nbfc|insur|capital market|holding).*"));

        Double priorCwip = null;
        Double priorPpe = null;
        try {
            Integer fy = FundamentalsHistoryService.fiscalYearOf(bs.getFinancialYear());
            if (fy != null && !isFinancial) {
                // The immediately preceding year only. A two-year-old balance sheet would
                // make the "change" span two years of spending while being reported as one.
                var prior = fundamentalsRepository.findBySymbolAndFiscalYear(symbol, fy - 1);
                if (prior.isPresent()) {
                    priorCwip = prior.get().getCapitalWorkInProgress();
                    // Net block too, now that the history row carries it (B-048). Without
                    // this the capex-to-depreciation ratio was permanently null in
                    // production, so the verdict could only ever speak about CWIP
                    // intensity — half the signal the feature was built for. Still null
                    // for a year recorded before the column existed, which is correct:
                    // that year genuinely has no stored net block.
                    priorPpe = prior.get().getNetBlock();
                }
            }
        } catch (Exception e) {
            log.debug("Prior-year capex lookup failed for {}: {}", symbol, e.getMessage());
        }

        return NseDataService.classifyCapexCycle(bs, isFinancial, priorCwip, priorPpe);
    }

    /** Years of stored history for a symbol — how close it is to a measurable capex delta. */
    public long historyYears(String symbol) {
        return fundamentalsRepository.countBySymbol(symbol);
    }

    /** Symbols whose stored history already spans two or more years. */
    public List<String> symbolsWithComparableYears() {
        return fundamentalsRepository.findSymbolsWithHistory(2);
    }
}
