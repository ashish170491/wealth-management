package com.example.trading.multibagger;

import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.example.trading.fundamentals.AnnualFundamentalsRepository;
import com.example.trading.holdings.SymbolVariants;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place the compounding lens (SPEC 41) is read from the database.
 *
 * <h2>Why this is a service and not a helper on each screen</h2>
 * <p>The lens is now shown on three surfaces - the screener, the stock page and the portfolio.
 * The rule table {@link CompoundingQuality} was already shared, but the <i>lookup</i> was not:
 * the stock page resolved its own screening row and counted its own years of accounts. A second
 * copy of that lookup on the portfolio is how one stock ends up reading Compounder on one screen
 * and "not measured" on the next - which is exactly the failure Gotcha 85 was written about, and
 * the reason its fix was centralisation rather than another patch.
 *
 * <p>So both callers come here, and by construction they cannot disagree: same window, same
 * symbol resolution, same depth map.
 *
 * <h2>Three things to keep if you edit this</h2>
 * <ol>
 *   <li><b>The bulk path is one query.</b> A portfolio is ~33 rows and each resolves through up
 *       to four symbol spellings; per-row lookups would be ~130 queries on a page load. The
 *       {@code IN} query costs one, which is what keeps the portfolio inside the SPEC 18 budget.</li>
 *   <li><b>Symbol resolution is Gotcha 84's, with one refinement: a row that cannot answer is
 *       not an answer (B-088).</b> Screening history is keyed on the NSE symbol and 22 of 33
 *       holdings are BSE-prefixed, so the composite is read across spellings - it describes the
 *       company, not the listing venue. But some BSE-keyed rows exist carrying no balance-sheet
 *       figures at all, and plain "first hit wins" picked those over the full NSE row, reporting
 *       RELIANCE, NTPC and NATIONALUM as "not measured" while the screener showed real verdicts
 *       for all three. So the first spelling with <i>figures to judge</i> wins; exact-first still
 *       decides between two rows that can both answer, and the reading records which spelling it
 *       used. This is not cherry-picking a nicer verdict - NOT_MEASURED is the absence of a
 *       verdict, not a bad one.</li>
 *   <li><b>Absent is not failed.</b> A stock that was never screened yields no reading at all and
 *       the caller renders "never screened". It must never become a NO verdict, and the depth map
 *       being empty must leave {@code yearsOfAccounts} null rather than zero (Gotcha 21, 44).</li>
 * </ol>
 *
 * <p>DB-only and cheap: one indexed query plus one grouped count. Safe on a page load.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompoundingLensService {

    /**
     * How far back a screening row may be and still describe the company. Matches the window the
     * stock page has always used, so the two surfaces resolve to the same row.
     */
    private static final int LOOKBACK_DAYS = 400;

    private final MultibaggerScoreRepository scoreRepository;
    private final AnnualFundamentalsRepository annualFundamentalsRepository;

    /**
     * One stock's reading.
     *
     * @param symbolAnswered the spelling that actually carried the screening history, which may
     *                       differ from the one asked for (Gotcha 84)
     */
    public record Reading(CompoundingQuality.Result result,
                          String symbolAnswered,
                          String symbolAsked,
                          LocalDate screeningDate) {

        /** True when the history came from a different exchange prefix than the caller used. */
        public boolean resolvedAcrossExchange() {
            return symbolAnswered != null && !symbolAnswered.equals(symbolAsked);
        }
    }

    /** @return the reading, or null when this stock has never been screened. */
    public Reading forSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return forSymbols(List.of(symbol)).get(symbol);
    }

    /**
     * Readings for many stocks in one query, keyed by the symbol as asked.
     *
     * <p>Symbols with no screening history are simply absent from the map - the caller renders
     * "never screened" rather than a verdict, because a business whose filings were never read is
     * not a business that failed.
     */
    public Map<String, Reading> forSymbols(Collection<String> symbols) {
        Map<String, Reading> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;

        // Every spelling of every symbol, so one IN query covers the whole portfolio.
        Set<String> candidates = new LinkedHashSet<>();
        Map<String, List<String>> variantsBySymbol = new LinkedHashMap<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) continue;
            List<String> variants = new ArrayList<>(SymbolVariants.candidates(symbol));
            variantsBySymbol.put(symbol, variants);
            candidates.addAll(variants);
        }
        if (candidates.isEmpty()) return out;

        Map<String, MultibaggerScoreEntity> latest = latestBySymbol(candidates);
        if (latest.isEmpty()) return out;

        Map<String, Integer> depth = yearsOfAccounts();
        Map<String, AnnualFundamentalsEntity> annual = latestAnnualBySymbol(candidates);

        for (Map.Entry<String, List<String>> e : variantsBySymbol.entrySet()) {
            Reading answered = null;   // first spelling whose row can actually answer
            Reading anyRow = null;     // first spelling with a row at all

            Integer years = depthFor(e.getValue(), depth);
            AnnualFundamentalsEntity accounts = annualFor(e.getValue(), annual);

            for (String variant : e.getValue()) {
                MultibaggerScoreEntity row = latest.get(variant);
                if (row == null) continue;

                // WHICH ROW WINS IS DECIDED ON THE SCREENING ROW ALONE (B-088, and the reason
                // this is not one expression). The annual fallback can give an otherwise empty
                // row figures to judge, so selecting on the post-fallback result let an empty
                // BSE row out-rank the full NSE one and re-created the very bug this loop was
                // written to fix - measured on NTPC, NATIONALUM and ABCAPITAL, which fell from
                // PARTIAL/COMPOUNDER/NO to a thinner reading the moment the fallback shipped.
                // The fallback fills gaps in the row we chose; it never chooses the row.
                boolean canAnswer = evaluate(row, years, null).applicable() > 0;
                CompoundingQuality.Result r = evaluate(row, years, accounts);
                Reading reading = new Reading(r, row.getSymbol(), e.getKey(), row.getScreeningDate());
                if (anyRow == null) anyRow = reading;
                if (canAnswer) {
                    answered = reading;
                    break;
                }
            }
            Reading chosen = answered != null ? answered : anyRow;
            if (chosen != null) out.put(e.getKey(), chosen);
        }
        return out;
    }

    /**
     * Years of accounts under whichever spelling has them (B-088).
     *
     * <p>The depth map is keyed by the symbol its rows were written under, which for a BSE-held
     * position is usually the NSE one. Looking it up by the screening row's own symbol alone made
     * the verdict claim less history than exists.
     */
    private static Integer depthFor(List<String> variants, Map<String, Integer> depth) {
        if (depth == null) return null;
        for (String v : variants) {
            Integer years = depth.get(v);
            if (years != null) return years;
        }
        return null;
    }

    /** Runs the pure rule table over one screening row. Contributes nothing to any score. */
    public CompoundingQuality.Result evaluate(MultibaggerScoreEntity e, Map<String, Integer> depth) {
        return evaluate(e, depth == null ? null : depth.get(e.getSymbol()));
    }

    /** Same, with the history depth already resolved across symbol spellings. */
    public CompoundingQuality.Result evaluate(MultibaggerScoreEntity e, Integer yearsOfAccounts) {
        return evaluate(e, yearsOfAccounts, null);
    }

    /**
     * Same, with the stored annual accounts available to fill gaps the screening row cannot
     * (SPEC §41.6). Passing null is the old behaviour exactly.
     */
    public CompoundingQuality.Result evaluate(MultibaggerScoreEntity e, Integer yearsOfAccounts,
                                              AnnualFundamentalsEntity annual) {
        CompoundingQuality.Input in = new CompoundingQuality.Input(
                e.getRocePercent(),
                e.getRoaPercent(),
                e.getCashConversionRatio(),
                e.getDebtToEquity(),
                e.getEarningsConsistencyScore(),
                e.getGrossMarginTrend(),
                e.getCapexVerdict(),
                e.getForensicFlags(),
                e.getFinancialQualityVerdict(),
                yearsOfAccounts);
        return CompoundingQuality.evaluate(withAnnualFallback(in, annual));
    }

    /**
     * Newest screening row per symbol inside the lookback window.
     *
     * <p>The query returns oldest-first, so writing every row into the map leaves the newest -
     * the same "last one wins" the stock page's trend lookup relies on.
     */
    private Map<String, MultibaggerScoreEntity> latestBySymbol(Collection<String> symbols) {
        Map<String, MultibaggerScoreEntity> map = new LinkedHashMap<>();
        try {
            LocalDate from = LocalDate.now().minusDays(LOOKBACK_DAYS);
            for (MultibaggerScoreEntity row : scoreRepository.findRecentForSymbols(symbols, from)) {
                map.put(row.getSymbol(), row);
            }
        } catch (Exception ex) {
            // WARN and say what the emptiness will look like downstream (B-054's rule): every
            // stock reads "never screened", which must not be mistaken for a considered negative.
            log.warn("Compounding lens: screening rows unavailable, so every stock will read "
                    + "'never screened' rather than carrying a verdict: {}", ex.getMessage());
        }
        return map;
    }

    /**
     * Years of annual accounts per symbol. Empty on failure, so depth reports as unknown rather
     * than as zero years - which would read as "no accounts at all" for every stock.
     */
    private Map<String, Integer> yearsOfAccounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        try {
            for (Object[] row : annualFundamentalsRepository.countYearsBySymbol()) {
                if (row.length >= 2 && row[0] != null && row[1] instanceof Number n) {
                    out.put(String.valueOf(row[0]), n.intValue());
                }
            }
        } catch (Exception ex) {
            log.warn("Compounding lens: annual-history depth unavailable, so every stock shows its "
                    + "track-record length as unknown rather than as measured: {}", ex.getMessage());
        }
        return out;
    }

    /**
     * Gate inputs derived from the latest year in {@code annual_fundamentals} (SPEC §41.6).
     *
     * <p><b>Why a second source exists.</b> The gates read the screening row, whose balance-sheet
     * fields come from the annual XBRL fetched live during screening. That fetch returns nothing
     * for a sizeable minority of stocks, and for those the lens reported "not measured" while the
     * <i>same figures</i> sat in {@code annual_fundamentals}, written from NSE's filing archive by
     * the §32.6 backfill - a different endpoint that succeeds where the other does not. Measured
     * on the live portfolio: GULPOLY, SKYGOLD and NITINSPIN each had 2-6 years on file and read
     * "not measured" on every screen.
     *
     * <p><b>It fills gaps only.</b> A screening-row figure always wins - it is the reading the
     * rest of the screen was computed from, and letting a second source override it would let one
     * stock's verdict disagree with its own composite. This adds a value only where there was
     * none, so it can turn NOT_MEASURED into a verdict and can never change one that already
     * existed.
     *
     * <p><b>The formulas are §12.8's, unchanged</b>, so a figure means the same thing whichever
     * source supplied it: ROCE = (PBT + finance costs) / (equity + borrowings), ROA = net profit /
     * total assets, cash conversion = real operating cash flow / net profit, debt-to-equity =
     * borrowings / equity. Earnings consistency and gross-margin trend are deliberately absent:
     * both are computed from the <i>quarterly</i> series and nothing in the annual table can
     * stand in for them (a substituted unit is the B-047 failure).
     *
     * <p>Every helper returns null rather than a default, and a zero or negative denominator
     * yields null rather than an infinity - "could not measure" is a valid answer here and a
     * fabricated ratio is not (Gotcha 21).
     */
    private static CompoundingQuality.Input withAnnualFallback(CompoundingQuality.Input in,
                                                               AnnualFundamentalsEntity a) {
        if (a == null) return in;
        return new CompoundingQuality.Input(
                in.rocePercent() != null ? in.rocePercent() : roce(a),
                in.roaPercent() != null ? in.roaPercent() : roa(a),
                in.cashConversionRatio() != null ? in.cashConversionRatio() : cashConversion(a),
                in.debtToEquity() != null ? in.debtToEquity() : debtToEquity(a),
                in.earningsConsistencyScore(),
                in.grossMarginTrend(),
                in.capexVerdict(),
                in.forensicFlags(),
                in.financialQualityVerdict(),
                in.yearsOfAccounts());
    }

    /** (PBT + finance costs) / (equity + borrowings), as a percentage. */
    private static Double roce(AnnualFundamentalsEntity a) {
        Double pbt = a.getProfitBeforeTax();
        Double equity = a.getEquity();
        if (pbt == null || equity == null) return null;
        double interest = a.getInterestCost() == null ? 0.0 : a.getInterestCost();
        double borrowings = a.getBorrowings() == null ? 0.0 : a.getBorrowings();
        double capital = equity + borrowings;
        if (capital <= 0) return null;
        return (pbt + interest) / capital * 100.0;
    }

    /** Net profit / total assets, as a percentage. The headline metric for a lender (§12.8). */
    private static Double roa(AnnualFundamentalsEntity a) {
        Double profit = a.getNetProfit();
        Double assets = a.getTotalAssets();
        if (profit == null || assets == null || assets <= 0) return null;
        return profit / assets * 100.0;
    }

    /** Real operating cash flow per rupee of profit - not the profit+depreciation proxy. */
    private static Double cashConversion(AnnualFundamentalsEntity a) {
        Double ocf = a.getOperatingCashFlow();
        Double profit = a.getNetProfit();
        if (ocf == null || profit == null || profit <= 0) return null;
        return ocf / profit;
    }

    private static Double debtToEquity(AnnualFundamentalsEntity a) {
        Double equity = a.getEquity();
        Double borrowings = a.getBorrowings();
        if (equity == null || borrowings == null || equity <= 0) return null;
        return borrowings / equity;
    }

    /**
     * Latest stored year per symbol, for the symbols asked about.
     *
     * <p>The query returns oldest-first, so writing every row leaves the newest - the same
     * "last one wins" the screening-row lookup uses.
     */
    private Map<String, AnnualFundamentalsEntity> latestAnnualBySymbol(Collection<String> symbols) {
        Map<String, AnnualFundamentalsEntity> map = new LinkedHashMap<>();
        try {
            for (AnnualFundamentalsEntity row : annualFundamentalsRepository.findHistoryForSymbols(symbols)) {
                map.put(row.getSymbol(), row);
            }
        } catch (Exception ex) {
            // WARN and name what the emptiness looks like downstream (B-054's rule): the fallback
            // silently not running shows up as "not measured" on stocks whose accounts are on file.
            log.warn("Compounding lens: annual accounts unavailable for the fallback, so stocks "
                    + "whose screening row lacks balance-sheet figures will read 'not measured' "
                    + "even where their filings are stored: {}", ex.getMessage());
        }
        return map;
    }

    /** The annual row under whichever spelling has one, in the same order as the screening row. */
    private static AnnualFundamentalsEntity annualFor(List<String> variants,
                                                      Map<String, AnnualFundamentalsEntity> annual) {
        for (String v : variants) {
            AnnualFundamentalsEntity row = annual.get(v);
            if (row != null) return row;
        }
        return null;
    }

}
