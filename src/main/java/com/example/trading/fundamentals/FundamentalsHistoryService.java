package com.example.trading.fundamentals;

import com.example.trading.ai.NseDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owns the long-horizon fundamentals table (SPEC.md §32): imports a decade of history from
 * a user-supplied export, and keeps it current from the annual XBRL pipeline.
 *
 * <p><b>Why an import at all.</b> NSE's integrated-filing feed starts ~Mar-2025 (B-017).
 * Ten-year CAGRs, through-cycle margins, multi-year deleveraging and share-count dilution
 * simply cannot be computed from it, and those are precisely the inputs that identify
 * turnarounds and serial diluters. Rather than build a second exchange integration on a
 * frozen endpoint family, this takes the same pragmatic route as the Zerodha tradebook
 * backfill (SPEC §9.3): parse an export the user already has access to, once, and let the
 * live pipeline maintain it from there.
 *
 * <p><b>Input format.</b> A screener.in "Data Sheet" style CSV — metric names down the
 * first column, financial years across the header. A transposed layout (one row per year)
 * is also accepted, since that is what a hand-built sheet usually looks like. XLSX is not
 * parsed: it would pull in a spreadsheet library for one import path, and "Save As CSV"
 * is a single step for the user.
 *
 * <p><b>Missing stays missing.</b> Every figure is nullable and an absent metric is left
 * null rather than defaulted to zero — a zero in {@code borrowings} reads as a debt-free
 * balance sheet and would flip a turnaround verdict on its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundamentalsHistoryService {

    private final AnnualFundamentalsRepository repository;
    private final NseDataService nseDataService;

    /** Minimum years before the multi-year detectors will express any opinion (SPEC §32.3). */
    public static final int MIN_YEARS_FOR_ANALYSIS = 4;

    // ---------------------------------------------------------------- import

    /** Outcome of one import, reported back to the caller in full — including what was skipped. */
    public record ImportResult(String symbol, int yearsParsed, int rowsCreated, int rowsUpdated,
                               int rowsSkippedXbrlWins, List<Integer> years, List<String> warnings) {
    }

    /**
     * Parse a fundamentals CSV export and upsert one row per financial year.
     *
     * @param symbol exchange-qualified symbol the file belongs to (e.g. {@code NSE:RELIANCE})
     * @param csv    raw CSV text
     */
    @Transactional
    public ImportResult importCsv(String symbol, String csv) {
        List<String> warnings = new ArrayList<>();
        Map<Integer, AnnualFundamentalsEntity> parsed = parse(symbol, csv, warnings);

        int created = 0;
        int updated = 0;
        int skipped = 0;
        for (Map.Entry<Integer, AnnualFundamentalsEntity> e : parsed.entrySet()) {
            var existing = repository.findBySymbolAndFiscalYear(symbol, e.getKey());
            if (existing.isPresent()) {
                AnnualFundamentalsEntity row = existing.get();
                // A row already sourced from the filing itself outranks a third-party
                // rendering of the same year. Overwriting it would quietly downgrade data.
                if ("XBRL".equals(row.getSource())) {
                    skipped++;
                    continue;
                }
                copyFigures(e.getValue(), row);
                repository.save(row);
                updated++;
            } else {
                repository.save(e.getValue());
                created++;
            }
        }

        log.info("Fundamentals import {}: {} years parsed, {} created, {} updated, {} left as XBRL",
                symbol, parsed.size(), created, updated, skipped);
        return new ImportResult(symbol, parsed.size(), created, updated, skipped,
                new ArrayList<>(parsed.keySet()), warnings);
    }

    /**
     * Record the current annual filing into the history table, so the table maintains
     * itself once the import bridge has covered the past.
     *
     * <p>Reads {@code fetchAnnualFinancials}, which is cached for 7 days — calling this
     * during a screening run costs nothing extra.
     *
     * @return true when a row was written or refreshed
     */
    @Transactional
    public boolean recordFromXbrl(String symbol) {
        String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
        return recordYear(symbol, nseDataService.fetchAnnualFinancials(tradingSymbol));
    }

    /**
     * One-time backfill of a symbol's pre-2025 annual history from NSE's own filing archive
     * (SPEC §32.5).
     *
     * <p>Replaces the manual screener.in CSV bridge for the common case. The archive behind
     * {@code corporates-financial-results} still serves 13-14 years of annual filings per symbol —
     * B-017 moved <em>discovery</em> of new filings to the integrated feed because that endpoint
     * stopped receiving them, not because the history went away. Those filings use the
     * {@code in-bse-fin} taxonomy this parser was written for, so nothing new has to be parsed.
     *
     * <p>The CSV import remains for anything the archive does not cover — a delisted predecessor,
     * a company whose filings pre-date 2011, or a figure the user has from another source.
     *
     * @return how many financial years were written or updated
     */
    public int backfillFromArchive(String symbol, int maxYears) {
        return backfillDetailed(symbol, maxYears).yearsWritten();
    }

    /**
     * What one symbol's backfill actually did, in the terms the status table needs (SPEC §32.6).
     *
     * <p>A bare "3 years written" cannot distinguish a company that has only ever filed three
     * annual results from one whose thirteen filings mostly failed to parse. The first is
     * COMPLETE and must never be retried; the second is PARTIAL and should be. Without this
     * distinction the batch picker re-queues the shallowest symbols forever and never reaches
     * the untouched ones — the natural failure of the obvious implementation.
     *
     * @param listingFailed true when the listing call itself failed, as opposed to the company
     *                      genuinely having no archive — a fetch failure must not be recorded as
     *                      a fact about the business
     */
    public record BackfillOutcome(String symbol, int listedYears, int yearsWritten,
                                  int mixedBasisSkipped, int nonMarchSkipped,
                                  boolean listingFailed, Boolean consolidatedBasis) {

        /** True when everything the archive offered was stored, so there is nothing left to fetch. */
        public boolean complete() {
            return !listingFailed && listedYears > 0
                    && yearsWritten + mixedBasisSkipped >= listedYears;
        }
    }

    public BackfillOutcome backfillDetailed(String symbol, int maxYears) {
        String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
        NseDataService.ArchiveFetch fetch =
                nseDataService.fetchAnnualArchiveDetailed(tradingSymbol, maxYears);
        List<NseDataService.BalanceSheetData> archive = fetch.years();
        if (archive.isEmpty()) {
            return new BackfillOutcome(symbol, fetch.listedYears(), 0, 0,
                    fetch.nonMarchSkipped(), fetch.listingFailed(), null);
        }

        boolean basis = seriesBasis(archive);
        int written = 0;
        int mixedBasisSkipped = 0;
        for (NseDataService.BalanceSheetData bs : archive) {
            if (bs.isConsolidated() != basis) {
                // Leave the year out rather than mix bases. A gap in the series is a fact the
                // downstream checks already handle (they refuse to measure across it); a mixed
                // series is a fabricated collapse or recovery they cannot detect at all.
                mixedBasisSkipped++;
                continue;
            }
            if (recordYear(symbol, bs)) written++;
        }
        // Written alongside attempted, and the skips named. A bare success count cannot reveal a
        // partial failure — that is how 210 of 288 screening rows went missing unnoticed (B-026).
        if (mixedBasisSkipped > 0) {
            log.info("Annual archive backfill {}: {} of {} years written ({} skipped - only a {} "
                            + "filing exists for those years and this series is {})",
                    symbol, written, archive.size(), mixedBasisSkipped,
                    basis ? "standalone" : "consolidated", basis ? "consolidated" : "standalone");
        } else {
            log.info("Annual archive backfill {}: {} of {} years written ({} basis)",
                    symbol, written, archive.size(), basis ? "consolidated" : "standalone");
        }
        return new BackfillOutcome(symbol, fetch.listedYears(), written, mixedBasisSkipped,
                fetch.nonMarchSkipped(), false, basis);
    }

    /**
     * Which reporting basis this symbol's series should be built on.
     *
     * <p>The majority basis across the fetched years, with consolidated winning a tie — a group
     * that reports consolidated at all is a group, and its standalone years are the anomaly. Kept
     * separate and package-private so the rule is testable without a network call.
     */
    static boolean seriesBasis(List<NseDataService.BalanceSheetData> archive) {
        long consolidated = archive.stream()
                .filter(NseDataService.BalanceSheetData::isConsolidated).count();
        return consolidated * 2 >= archive.size();
    }

    /** Merge one parsed annual filing into the symbol's row for that financial year. */
    private boolean recordYear(String symbol, NseDataService.BalanceSheetData bs) {
        try {
            if (bs == null) return false;

            Integer fy = fiscalYearOf(bs.getFinancialYear());
            if (fy == null) return false;

            // A filing that parsed structurally but yielded no figures must not become a row.
            // An empty row is worse than a missing one: `yearsAvailable` counts it, so the
            // forensic screen and durability report more history than they can actually read —
            // and because the row is stamped XBRL, the import path can never replace it
            // (Gotcha 49). Same failure class as B-046, one layer up.
            if (!hasAnyFigure(bs)) {
                log.debug("Annual fundamentals: {} FY{} parsed but carried no figures - not stored",
                        symbol, fy);
                return false;
            }

            AnnualFundamentalsEntity row = repository.findBySymbolAndFiscalYear(symbol, fy)
                    .orElseGet(() -> AnnualFundamentalsEntity.builder()
                            .symbol(symbol).fiscalYear(fy).build());

            // Non-null merge (B-046). These were unconditional setters, so a field the
            // filing does not tag — NSE's coverage varies by company and by taxonomy —
            // overwrote a real imported figure with null. The row is marked XBRL either
            // way, so the erased value could never be restored by a re-import: the import
            // path deliberately refuses to overwrite an XBRL row (§32.1, Gotcha 49). The
            // filing outranks the import where it has a figure, and only there.
            row.setSource("XBRL");
            row.setConsolidated(bs.isConsolidated());
            setIfPresent(bs.getRevenue(), row::setSales);
            setIfPresent(bs.getNetProfit(), row::setNetProfit);
            setIfPresent(bs.getFinanceCosts(), row::setInterestCost);
            setIfPresent(bs.getDepreciation(), row::setDepreciation);
            setIfPresent(bs.getTotalBorrowings(), row::setBorrowings);
            setIfPresent(bs.getEquity(), row::setEquity);
            setIfPresent(bs.getTotalAssets(), row::setTotalAssets);
            setIfPresent(bs.getCapitalWorkInProgress(), row::setCapitalWorkInProgress);
            setIfPresent(bs.getOperatingCashFlow(), row::setOperatingCashFlow);
            // Net block, so next year's capex-to-depreciation ratio has a prior year to
            // work from — the filing itself carries no comparative balance sheet (B-034).
            setIfPresent(bs.getPropertyPlantEquipment(), row::setNetBlock);
            // Receivables and share count: the two inputs the forensic screen could never
            // measure for an XBRL-sourced year, because nothing wrote them (B-046).
            setIfPresent(bs.getTradeReceivables(), row::setReceivables);
            setIfPresent(bs.getSharesOutstandingCr(), row::setShareCount);
            // Operating profit is not tagged directly; PBT + finance costs is the closest
            // honest reconstruction, and only when both are present.
            if (bs.getProfitBeforeTax() != null && bs.getFinanceCosts() != null) {
                row.setOperatingProfit(bs.getProfitBeforeTax() + bs.getFinanceCosts());
            }
            // Schema lock, SPEC §32.6. These four were all being read from the filing and thrown
            // away. They are written here rather than in a later pass because a year already
            // stamped source=XBRL is unreachable by the import path (Gotcha 49), so a column
            // added afterwards could only be filled by re-fetching every filing.
            setIfPresent(bs.getProfitBeforeTax(), row::setProfitBeforeTax);
            setIfPresent(bs.getDividendsPaid(), row::setDividendsPaid);
            setIfPresent(bs.getFaceValue(), row::setFaceValue);
            applyAvailability(row, bs, fy);
            repository.save(row);
            return true;
        } catch (Exception e) {
            log.debug("Annual fundamentals record failed for {}: {}", symbol, e.getMessage());
            return false;
        }
    }

    /**
     * Share-count setter that normalises the unit to crore (B-046).
     *
     * <p>A screener sheet may report the absolute count (1,252,950,000) or the count in
     * crore (1,252.95); the XBRL pipeline always writes crore. Mixing the two inside one
     * symbol's series produces a 10-million-fold step that the dilution check would read as
     * a corporate event. The threshold is unambiguous in this domain: no listed Indian
     * company has 100,000 crore shares (that would be 10^12 shares), so anything above it
     * is an absolute count. Same auto-detection pattern as the FII/DII lakhs-vs-crores
     * conversion.
     */
    private static java.util.function.BiConsumer<AnnualFundamentalsEntity, Double> shareCountSetter() {
        return (row, v) -> {
            if (v == null) return;
            row.setShareCount(v > ABSOLUTE_SHARE_COUNT_THRESHOLD ? v / 1_00_00_000.0 : v);
        };
    }

    /** Above this, a share count is an absolute number of shares rather than crore. */
    static final double ABSOLUTE_SHARE_COUNT_THRESHOLD = 1_00_000;

    /** True when the parsed filing carries at least one figure worth storing. */
    private static boolean hasAnyFigure(NseDataService.BalanceSheetData bs) {
        return bs.getRevenue() != null || bs.getNetProfit() != null || bs.getEquity() != null
                || bs.getTotalAssets() != null || bs.getTotalBorrowings() != null
                || bs.getOperatingCashFlow() != null || bs.getTradeReceivables() != null
                || bs.getPropertyPlantEquipment() != null || bs.getSharesOutstandingCr() != null;
    }

    /** Write only what the filing actually reported; never blank an existing figure. */
    private static void setIfPresent(Double value, java.util.function.Consumer<Double> setter) {
        if (value != null) setter.accept(value);
    }

    // ---------------------------------------------------------------- point-in-time availability

    /**
     * Months after a 31-March year end by which audited annual results are reliably public.
     *
     * <p>SEBI LODR Reg 33(3) allows 60 days for audited annual results, and companies use them.
     * Five months is deliberately more conservative than the regulation: an estimate used in a
     * back-test must err <em>late</em>, because erring early is look-ahead bias and biases the
     * result in the flattering direction. A lens that "knew" the result before the market did
     * will always look prescient.
     */
    static final int ESTIMATED_FILING_LAG_MONTHS = 5;

    /**
     * Record when this year's figures became public, and whether that date is filed or assumed.
     *
     * <p>Never overwrites a real filed date with an estimate: once a row has a broadcast date
     * from the archive, a later re-run that cannot parse one must leave it alone. The reverse is
     * allowed — a filed date always replaces an estimate.
     */
    private static void applyAvailability(AnnualFundamentalsEntity row,
                                          NseDataService.BalanceSheetData bs, int fiscalYear) {
        java.time.LocalDate filed = parseFilingDate(bs.getFilingDate());
        if (filed != null) {
            row.setAvailableFrom(filed);
            row.setAvailableFromEstimated(false);
            return;
        }
        boolean haveFiled = row.getAvailableFrom() != null
                && Boolean.FALSE.equals(row.getAvailableFromEstimated());
        if (haveFiled) return;
        row.setAvailableFrom(java.time.LocalDate.of(fiscalYear, 3, 31)
                .plusMonths(ESTIMATED_FILING_LAG_MONTHS));
        row.setAvailableFromEstimated(true);
    }

    /**
     * Parse the archive's broadcast date. NSE is inconsistent across the years this reaches
     * back over, so several shapes are accepted; anything unrecognised returns null and the
     * caller falls back to the conservative estimate rather than to today's date.
     */
    static java.time.LocalDate parseFilingDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        int space = s.indexOf(' ');
        if (space > 0) s = s.substring(0, space);       // drop a trailing time-of-day
        String[] patterns = {"dd-MMM-yyyy", "yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "dd-MMM-yy"};
        for (String p : patterns) {
            try {
                return java.time.LocalDate.parse(s, java.time.format.DateTimeFormatter
                        .ofPattern(p, Locale.ENGLISH));
            } catch (Exception ignored) {
                // Try the next shape.
            }
        }
        return null;
    }

    public List<AnnualFundamentalsEntity> history(String symbol) {
        return repository.findHistory(symbol);
    }

    public long yearsAvailable(String symbol) {
        return repository.countBySymbol(symbol);
    }

    /** Symbols with enough years for the multi-year detectors to say anything at all. */
    public List<String> symbolsWithHistory() {
        return repository.findSymbolsWithHistory(MIN_YEARS_FOR_ANALYSIS);
    }

    @Transactional
    public void deleteHistory(String symbol) {
        repository.deleteBySymbol(symbol);
    }

    // ---------------------------------------------------------------- parsing

    /** Metric label fragment -> setter. Matched case-insensitively against the row label. */
    private static final List<Map.Entry<String, java.util.function.BiConsumer<AnnualFundamentalsEntity, Double>>> FIELDS =
            List.of(
                    Map.entry("operating profit", (java.util.function.BiConsumer<AnnualFundamentalsEntity, Double>) AnnualFundamentalsEntity::setOperatingProfit),
                    Map.entry("net profit", AnnualFundamentalsEntity::setNetProfit),
                    Map.entry("sales", AnnualFundamentalsEntity::setSales),
                    Map.entry("revenue", AnnualFundamentalsEntity::setSales),
                    Map.entry("interest", AnnualFundamentalsEntity::setInterestCost),
                    Map.entry("depreciation", AnnualFundamentalsEntity::setDepreciation),
                    Map.entry("borrowings", AnnualFundamentalsEntity::setBorrowings),
                    Map.entry("receivables", AnnualFundamentalsEntity::setReceivables),
                    Map.entry("capital work in progress", AnnualFundamentalsEntity::setCapitalWorkInProgress),
                    Map.entry("cash from operating activity", AnnualFundamentalsEntity::setOperatingCashFlow),
                    Map.entry("no. of equity shares", shareCountSetter()),
                    Map.entry("number of equity shares", shareCountSetter()),
                    Map.entry("total assets", AnnualFundamentalsEntity::setTotalAssets));

    /**
     * Equity is reported as two lines on a screener sheet (share capital + reserves) and
     * must be summed, so it is handled outside {@link #FIELDS}.
     */
    private static final String EQUITY_CAPITAL = "equity share capital";
    private static final String RESERVES = "reserves";

    Map<Integer, AnnualFundamentalsEntity> parse(String symbol, String csv, List<String> warnings) {
        Map<Integer, AnnualFundamentalsEntity> byYear = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) {
            warnings.add("Empty file");
            return byYear;
        }

        List<String[]> rows = new ArrayList<>();
        for (String line : csv.split("\r?\n")) {
            if (!line.isBlank()) rows.add(splitCsvLine(line));
        }
        if (rows.isEmpty()) {
            warnings.add("No readable rows");
            return byYear;
        }

        // Headers are re-detected as the file is walked, not located once (B-047). A
        // screener "Data Sheet" is several stacked sections — annual P&L, then a QUARTERS
        // block, then Balance Sheet and Cash Flow — each with its own header row. Taking the
        // first header and applying its column->year map to everything below it meant the
        // quarterly block's Sales and Net Profit were written into the *annual* fields of
        // whatever years those columns happened to map to. Every CAGR, margin, turnaround
        // and forensic verdict downstream then ran on a single quarter labelled as a year.
        Map<Integer, Integer> colToYear = new LinkedHashMap<>();
        boolean sawAnnualHeader = false;
        boolean active = false;
        int quarterlyBlocksSkipped = 0;

        Map<Integer, Double> equityCapital = new LinkedHashMap<>();
        Map<Integer, Double> reserves = new LinkedHashMap<>();

        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);

            Map<Integer, Integer> found = yearColumns(row);
            if (found.size() >= 2) {
                if (isAnnualHeader(row, found)) {
                    colToYear = found;
                    sawAnnualHeader = true;
                    active = true;
                    for (Integer year : found.values()) {
                        byYear.computeIfAbsent(year, y -> AnnualFundamentalsEntity.builder()
                                .symbol(symbol).fiscalYear(y).source("IMPORT").build());
                    }
                } else {
                    // A quarterly (or otherwise non-annual) header: everything under it is
                    // skipped until the next annual header restores the mapping.
                    active = false;
                    quarterlyBlocksSkipped++;
                }
                continue;   // a header row is never also a data row
            }

            if (!active || row.length < 2) continue;
            String label = row[0].trim().toLowerCase(Locale.ENGLISH);
            if (label.isEmpty() || isDerivedMetric(label)) continue;

            for (Map.Entry<Integer, Integer> ce : colToYear.entrySet()) {
                int col = ce.getKey();
                int year = ce.getValue();
                if (col >= row.length) continue;
                Double v = parseNumber(row[col]);
                if (v == null) continue;

                AnnualFundamentalsEntity target = byYear.get(year);
                if (label.startsWith(EQUITY_CAPITAL)) {
                    equityCapital.put(year, v);
                } else if (label.startsWith(RESERVES)) {
                    reserves.put(year, v);
                } else {
                    for (var f : FIELDS) {
                        if (label.startsWith(f.getKey())) {
                            f.getValue().accept(target, v);
                            break;
                        }
                    }
                }
            }
        }

        if (!sawAnnualHeader) {
            warnings.add("Could not find a header row with at least two annual financial-year columns "
                    + "(expected labels like 'Mar-2018' or 'FY2018' across the top). "
                    + (quarterlyBlocksSkipped > 0
                    ? "A quarterly block was found and skipped — this looks like a quarterly export."
                    : ""));
            return byYear;
        }
        if (quarterlyBlocksSkipped > 0) {
            // Say what was ignored. A silently dropped section reads as "the file did not
            // contain that data" (SPEC §21 rule 7).
            warnings.add(quarterlyBlocksSkipped + " quarterly block(s) skipped — this table stores "
                    + "annual figures only, and a quarter written into a year would corrupt every "
                    + "CAGR and margin computed from it.");
        }

        // Net worth = share capital + reserves. Written only when at least one side is
        // present; a lone reserves line is still a usable approximation of equity.
        for (Integer year : byYear.keySet()) {
            Double cap = equityCapital.get(year);
            Double res = reserves.get(year);
            if (cap != null || res != null) {
                byYear.get(year).setEquity((cap == null ? 0 : cap) + (res == null ? 0 : res));
            }
        }

        // Drop years where nothing at all parsed, so an empty column never counts as a
        // year of history the detectors would then trust.
        byYear.entrySet().removeIf(e -> isEmpty(e.getValue()));
        if (byYear.isEmpty()) {
            warnings.add("Found year columns but no recognisable metric rows "
                    + "(expected labels such as 'Sales', 'Net profit', 'Borrowings')");
        }
        return byYear;
    }

    /** Columns of a row that carry a parseable financial year, keyed by column index. */
    private static Map<Integer, Integer> yearColumns(String[] row) {
        Map<Integer, Integer> found = new LinkedHashMap<>();
        for (int c = 1; c < row.length; c++) {
            Integer y = fiscalYearOf(row[c]);
            if (y != null) found.put(c, y);
        }
        return found;
    }

    /**
     * Is this header a row of financial <i>years</i>, or a row of quarters (B-047)?
     *
     * <p>Two tells, either of which is decisive. A quarterly header repeats a year across
     * columns (Jun-2024, Sep-2024, Dec-2024 all parse to 2024), and it names months other
     * than March. Indian annual reporting ends 31 March, so an annual header is either
     * all-March or carries no month at all ("FY2024", "2024").
     *
     * <p>Deliberately conservative: an ambiguous header is treated as annual, because a
     * skipped annual block loses data the user supplied, while a mis-read quarterly block
     * writes a quarter into a year — and that is the failure this exists to prevent.
     */
    private static boolean isAnnualHeader(String[] row, Map<Integer, Integer> found) {
        if (found.size() != new java.util.HashSet<>(found.values()).size()) {
            return false;   // a year appears twice: quarters of the same year
        }
        for (Integer col : found.keySet()) {
            String month = monthOf(row[col]);
            if (month != null && !"mar".equals(month)) return false;
        }
        return true;
    }

    /** Three-letter month token in a column label, lower-cased, or null when absent. */
    private static String monthOf(String raw) {
        if (raw == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(raw);
        // The LAST month token wins, so NSE's "01-Apr-2023 To 31-Mar-2024" reads as March —
        // consistent with fiscalYearOf(), which takes the last year for the same reason.
        String last = null;
        while (m.find()) last = m.group().toLowerCase(Locale.ENGLISH);
        return last;
    }

    /**
     * Rows that are ratios, growth rates or per-share figures rather than rupee amounts.
     *
     * <p>These must be rejected <i>before</i> label matching, not by it. A screener sheet
     * carries "Sales" and "Sales Growth %" as adjacent rows, and since the second starts
     * with the first, prefix matching alone quietly writes a 16 (percent) into the sales
     * field — a three-orders-of-magnitude error that would then flow into every CAGR,
     * margin and turnaround verdict downstream. Caught by
     * {@code FundamentalsImportTest.percentageRowsDoNotOverwriteFigures}.
     */
    private static boolean isDerivedMetric(String label) {
        return label.contains("%") || label.contains("growth") || label.contains("margin")
                || label.contains("ratio") || label.contains("per share") || label.contains("yield")
                || label.contains("cagr") || label.contains("roce") || label.contains("roe")
                || label.contains("days") || label.contains("turnover");
    }

    private static boolean isEmpty(AnnualFundamentalsEntity e) {
        return e.getSales() == null && e.getNetProfit() == null && e.getOperatingProfit() == null
                && e.getBorrowings() == null && e.getEquity() == null && e.getShareCount() == null
                && e.getOperatingCashFlow() == null && e.getReceivables() == null;
    }

    /**
     * Merge an imported year into an existing IMPORT row, field by field.
     *
     * <p>This was a wholesale copy <b>including nulls</b>, unlike the XBRL path's
     * {@code setIfPresent} — so re-importing a narrower CSV over an earlier one erased every
     * figure the second sheet happened to omit. That is B-046 in the one path that never got the
     * fix: the same defect, the same blast radius (a null borrowings reads as a debt-free balance
     * sheet), differing only in which writer causes it.
     *
     * <p>The consequence is that a re-import can only ever add. Correcting a wrong figure now
     * means deleting the symbol's history first, which is the safer default — a silent erase is
     * undetectable, while a stale figure is at least visible in the history endpoint.
     */
    private static void copyFigures(AnnualFundamentalsEntity from, AnnualFundamentalsEntity to) {
        setIfPresent(from.getSales(), to::setSales);
        setIfPresent(from.getOperatingProfit(), to::setOperatingProfit);
        setIfPresent(from.getNetProfit(), to::setNetProfit);
        setIfPresent(from.getInterestCost(), to::setInterestCost);
        setIfPresent(from.getDepreciation(), to::setDepreciation);
        setIfPresent(from.getBorrowings(), to::setBorrowings);
        setIfPresent(from.getEquity(), to::setEquity);
        setIfPresent(from.getTotalAssets(), to::setTotalAssets);
        setIfPresent(from.getReceivables(), to::setReceivables);
        setIfPresent(from.getCapitalWorkInProgress(), to::setCapitalWorkInProgress);
        setIfPresent(from.getOperatingCashFlow(), to::setOperatingCashFlow);
        setIfPresent(from.getShareCount(), to::setShareCount);
        setIfPresent(from.getNetBlock(), to::setNetBlock);
        setIfPresent(from.getDividendsPaid(), to::setDividendsPaid);
        setIfPresent(from.getProfitBeforeTax(), to::setProfitBeforeTax);
        setIfPresent(from.getFaceValue(), to::setFaceValue);
        to.setSource("IMPORT");
    }

    /**
     * Extract the financial year a label ends in. Accepts "Mar-2024", "Mar 2024",
     * "FY2024", "2024", and NSE's own "01-Apr-2023 To 31-Mar-2024" (the end year wins).
     * Returns null for anything else, which is how non-year columns get ignored.
     */
    static Integer fiscalYearOf(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\"", "");
        if (s.isEmpty()) return null;

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(s);
        Integer last = null;
        while (m.find()) {
            last = Integer.parseInt(m.group());
        }
        if (last == null) return null;
        // Sanity band: rules out phone numbers, row counts and stray identifiers.
        return (last >= 1990 && last <= 2100) ? last : null;
    }

    /** Parse a cell to a number, tolerating commas, currency marks, %, and bracketed negatives. */
    static Double parseNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\"", "").replace(",", "")
                .replace("₹", "").replace("%", "").trim();
        if (s.isEmpty() || "-".equals(s) || "NA".equalsIgnoreCase(s) || "N/A".equalsIgnoreCase(s)) return null;
        boolean negative = s.startsWith("(") && s.endsWith(")");
        if (negative) s = s.substring(1, s.length() - 1).trim();
        try {
            double v = Double.parseDouble(s);
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Split one CSV line, honouring double-quoted fields containing commas. */
    static String[] splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
