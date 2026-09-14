package com.example.trading.api;

import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.example.trading.fundamentals.ForensicScreenService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.fundamentals.FundamentalsBackfillService;
import com.example.trading.fundamentals.FundamentalsHistoryService;
import com.example.trading.fundamentals.LongHorizonRecordService;
import com.example.trading.fundamentals.TurnaroundDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Long-horizon fundamentals: history import, turnaround detection, forensic flags
 * (SPEC.md §32).
 *
 * <p>Every GET here is DB-only and dashboard-safe. {@code /forensics/{symbol}} takes an
 * {@code announcements} flag which, when true, makes one live NSE call — it is false by
 * default so a page load can never trigger it (SPEC §27.4).
 */
@Slf4j
@RestController
@RequestMapping("/api/fundamentals")
@RequiredArgsConstructor
public class FundamentalsController {

    private final FundamentalsHistoryService historyService;
    private final TurnaroundDetectionService turnaroundService;
    private final ForensicScreenService forensicService;
    private final com.example.trading.persistence.HoldingsRepository holdingsRepository;
    private final com.example.trading.fundamentals.FundamentalsBackfillService backfillService;
    private final com.example.trading.fundamentals.FundamentalsBackfillConfig backfillConfig;
    private final LongHorizonRecordService longHorizonService;

    /**
     * Import a decade of fundamentals for one stock from a CSV export.
     *
     * <p>Accepts either a multipart {@code file=} upload or a raw {@code text/csv} body:
     * <pre>
     * curl -X POST "http://localhost:8080/api/fundamentals/import-history?symbol=NSE:RELIANCE" \
     *      -H "Content-Type: text/csv" --data-binary @reliance.csv
     * </pre>
     */
    @PostMapping(value = "/import-history", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importMultipart(@RequestParam String symbol,
                                                               @RequestParam("file") MultipartFile file) {
        try {
            String csv = new String(file.getBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok(toBody(historyService.importCsv(normalize(symbol), csv)));
        } catch (Exception e) {
            log.warn("Fundamentals import failed for {}: {}", symbol, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping(value = "/import-history", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ResponseEntity<Map<String, Object>> importRaw(@RequestParam String symbol,
                                                         @RequestBody String csv) {
        try {
            return ResponseEntity.ok(toBody(historyService.importCsv(normalize(symbol), csv)));
        } catch (Exception e) {
            log.warn("Fundamentals import failed for {}: {}", symbol, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", String.valueOf(e.getMessage())));
        }
    }

    /** Stored annual history for a stock, oldest year first. DB-only. */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam String symbol) {
        String sym = normalize(symbol);
        List<AnnualFundamentalsEntity> rows = historyService.history(sym);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AnnualFundamentalsEntity r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fiscalYear", r.getFiscalYear());
            m.put("source", r.getSource());
            m.put("sales", r.getSales());
            m.put("operatingProfit", r.getOperatingProfit());
            m.put("operatingMarginPercent", r.operatingMarginPercent());
            m.put("netProfit", r.getNetProfit());
            m.put("interestCost", r.getInterestCost());
            m.put("borrowings", r.getBorrowings());
            m.put("equity", r.getEquity());
            m.put("debtToEquity", r.debtToEquity());
            m.put("receivables", r.getReceivables());
            m.put("operatingCashFlow", r.getOperatingCashFlow());
            m.put("shareCount", r.getShareCount());
            m.put("capitalWorkInProgress", r.getCapitalWorkInProgress());
            m.put("netBlock", r.getNetBlock());
            m.put("profitBeforeTax", r.getProfitBeforeTax());
            m.put("dividendsPaid", r.getDividendsPaid());
            m.put("faceValue", r.getFaceValue());
            m.put("consolidated", r.getConsolidated());
            // Point-in-time availability (SPEC §32.6). The flag travels with the date so a
            // consumer can never read an assumed filing date as a filed one.
            m.put("availableFrom", r.getAvailableFrom());
            m.put("availableFromEstimated", r.getAvailableFromEstimated());
            out.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "SUCCESS");
        body.put("symbol", sym);
        body.put("yearsAvailable", rows.size());
        body.put("minYearsForAnalysis", FundamentalsHistoryService.MIN_YEARS_FOR_ANALYSIS);
        body.put("years", out);
        return ResponseEntity.ok(body);
    }

    /** Turnaround verdict for a stock. DB-only. */
    @GetMapping("/turnaround/{symbol}")
    public ResponseEntity<TurnaroundDetectionService.TurnaroundResult> turnaround(@PathVariable String symbol) {
        return ResponseEntity.ok(turnaroundService.detect(normalize(symbol)));
    }

    /** Every stock with enough history that currently reads as a turnaround. DB-only. */
    @GetMapping("/turnarounds")
    public ResponseEntity<Map<String, Object>> turnarounds() {
        List<TurnaroundDetectionService.TurnaroundResult> hits = new ArrayList<>();
        for (String sym : historyService.symbolsWithHistory()) {
            var r = turnaroundService.detect(sym);
            if (r.isCandidate()) hits.add(r);
        }
        hits.sort((a, b) -> Integer.compare(b.getCriteriaMet(), a.getCriteriaMet()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "SUCCESS");
        body.put("count", hits.size());
        body.put("candidates", hits);
        return ResponseEntity.ok(body);
    }

    /**
     * Forensic red flags for a stock.
     *
     * @param announcements when true, also scans NSE corporate announcements for auditor
     *                      and related-party items. Defaults to false: that is a live NSE
     *                      call and this endpoint must stay safe to hit from a page load.
     */
    @GetMapping("/forensics/{symbol}")
    public ResponseEntity<ForensicScreenService.ForensicResult> forensics(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "false") boolean announcements) {
        return ResponseEntity.ok(forensicService.screen(normalize(symbol), announcements));
    }

    /**
     * Backfill a symbol's annual history from NSE's own filing archive (SPEC §32.5).
     *
     * <p>Does what the CSV import does, without the CSV. NSE still serves 13-14 years of annual
     * filings per symbol behind the legacy {@code corporates-financial-results} endpoint, each
     * with its Ind-AS XBRL, and this app already parses that taxonomy.
     *
     * <p><b>Live NSE calls</b> — one listing plus one XBRL download per year, so roughly a dozen
     * requests per symbol. POST precisely so no page load can reach it, and blocked from 09:40
     * because the FII/DII jobs at 09:45 and 10:00 need the same NSE session.
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "10") int maxYears) {
        requireOutsideNseCrunch();
        String sym = normalize(symbol);
        long before = historyService.yearsAvailable(sym);
        int written = historyService.backfillFromArchive(sym, maxYears);
        long after = historyService.yearsAvailable(sym);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", after > before ? "SUCCESS" : written > 0 ? "REFRESHED" : "NO_DATA");
        body.put("symbol", sym);
        body.put("yearsWritten", written);
        body.put("yearsBefore", before);
        body.put("yearsAfter", after);
        if (after == before && written == 0) {
            body.put("note", "NSE's archive returned nothing usable for this symbol. Recently listed "
                    + "companies have few filings; a renamed or delisted predecessor has none under "
                    + "this ticker. The CSV import remains the fallback.");
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Backfill every currently-held stock. Roughly a dozen NSE calls per holding, so a 33-stock
     * portfolio is ~360 requests: a POST, so no page load can reach it.
     *
     * <p><b>Now genuinely paced</b> (defect 7.1). This javadoc claimed pacing for months while
     * there was none anywhere in the path — the loop over holdings and the loop over years were
     * both tight, against the host that has already bot-walled {@code /api/quote-equity}
     * permanently (B-018). Spacing is applied process-wide in {@code NseDataService.pace()} and
     * configured by {@code trading.fundamentals.backfill.pace-ms}, so this path and the universe
     * batch share one budget rather than each respecting its own and together breaking the real
     * one. At the default spacing a full portfolio run is roughly seven minutes.
     */
    @PostMapping("/backfill-holdings")
    public ResponseEntity<Map<String, Object>> backfillHoldings(
            @RequestParam(defaultValue = "10") int maxYears) {
        requireOutsideNseCrunch();
        List<HoldingsEntity> holdings = holdingsRepository.findActive();

        Map<String, Object> perSymbol = new LinkedHashMap<>();
        int totalYears = 0;
        int covered = 0;
        boolean stoppedEarly = false;
        for (HoldingsEntity h : holdings) {
            if (h.getSymbol() == null) continue;
            if (shouldStopForNseCrunch()) {
                // Defect 7.2: the entry guard cannot stop a run that has already started.
                stoppedEarly = true;
                log.warn("Archive backfill: stopping at the 09:40 NSE window with {} of {} holdings "
                        + "done. Everything written so far is kept; re-run after 10:15 for the rest.",
                        perSymbol.size(), holdings.size());
                break;
            }
            try {
                int written = historyService.backfillFromArchive(h.getSymbol(), maxYears);
                long after = historyService.yearsAvailable(h.getSymbol());
                totalYears += written;
                if (after >= 3) covered++;
                perSymbol.put(h.getSymbol(), Map.of("yearsWritten", written, "yearsOnFile", after));
            } catch (Exception e) {
                // One symbol's failure must not lose the other thirty-two.
                log.warn("Archive backfill failed for {}: {}", h.getSymbol(), e.getMessage());
                perSymbol.put(h.getSymbol(), Map.of("error", String.valueOf(e.getMessage())));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", stoppedEarly ? "STOPPED_AT_NSE_WINDOW" : "DONE");
        body.put("holdings", holdings.size());
        body.put("yearsWritten", totalYears);
        body.put("holdingsWithThreeOrMoreYears", covered);
        if (stoppedEarly) {
            body.put("stopReason", "Reached the 09:40-10:15 window the FII/DII jobs need. "
                    + "Completed symbols are saved; re-run after 10:15 to finish the rest.");
        }
        body.put("note", "Three years is what the forensic screen needs, which is what unlocks the "
                + "core-holding quorum (SPEC §35.4). Four unlocks the dilution check and durability D1.");
        body.put("perSymbol", perSymbol);
        return ResponseEntity.ok(body);
    }

    /**
     * What management did with a decade of cash, and whether the business actually compounded
     * (SPEC §42, §43).
     *
     * <p>DB-only and dashboard-safe: two queries, no network call. Returns a
     * {@code NOT_MEASURED} record rather than a 404 when there is no history, because "nobody has
     * backfilled this symbol yet" and "this company has a poor record" must never render alike
     * (SPEC §21 rule 7).
     *
     * <p>Neither reading contributes a single point to any score (Gotcha 30, SPEC §20 rule 9).
     */
    @GetMapping("/long-horizon")
    public ResponseEntity<LongHorizonRecordService.LongHorizonView> longHorizon(
            @RequestParam String symbol) {
        return ResponseEntity.ok(longHorizonService.view(normalize(symbol)));
    }

    /**
     * How deep the universe's annual history is, and what the backfill still owes (SPEC §32.6).
     *
     * <p><b>DB-only and dashboard-safe.</b> It reads two tables and makes no network call — but
     * note that a page-load {@code get()} is ungated (Gotcha 39), so that claim was verified by
     * hand rather than inherited from this javadoc. A "DB-only" comment is load-bearing and has
     * been wrong before (B-039).
     */
    @GetMapping("/coverage")
    public ResponseEntity<FundamentalsBackfillService.CoverageReport> coverage() {
        return ResponseEntity.ok(backfillService.coverage());
    }

    /**
     * Run one backfill batch now, instead of waiting for the 11:30 weekday scheduler.
     *
     * <p>POST, live NSE calls, and the same 09:40-10:15 refusal. {@code limit} defaults to the
     * configured batch size; raising it well beyond that is how a manual call starts contending
     * with the 14:00 screening for the single NSE session, which is why the service stops at its
     * own configured deadline regardless of what is asked for here.
     */
    @PostMapping("/backfill-universe")
    public ResponseEntity<FundamentalsBackfillService.BatchResult> backfillUniverse(
            @RequestParam(required = false) Integer limit) {
        requireOutsideNseCrunch();
        int n = limit != null && limit > 0 ? limit : backfillConfig.getBatchSize();
        return ResponseEntity.ok(backfillService.runBatch(n));
    }

    /**
     * The FII/DII fetch at 09:45 and its report at 10:00 share this app's single NSE session and
     * cookie jar. A multi-minute backfill running across them is the same class of interference as
     * a manual Kite sweep starting at 15:20 (B-049) — the damage lands on a report the user was
     * expecting, not on the request that caused it.
     */
    private void requireOutsideNseCrunch() {
        if (!insideNseCrunch()) return;
        throw new BackfillBlockedException(String.format(
                "The annual-history backfill makes several minutes of live NSE calls and is blocked "
                        + "between %s and %s IST: the FII/DII fetch at 09:45 and its report at 10:00 "
                        + "share the same NSE session. Run it before %s or after %s IST.",
                NSE_CRUNCH_START, NSE_CRUNCH_END, NSE_CRUNCH_START, NSE_CRUNCH_END));
    }

    private static boolean insideNseCrunch() {
        java.time.LocalTime now = java.time.ZonedDateTime
                .now(java.time.ZoneId.of("Asia/Kolkata")).toLocalTime();
        return !now.isBefore(NSE_CRUNCH_START) && !now.isAfter(NSE_CRUNCH_END);
    }

    /**
     * Stop a running backfill when it reaches the protected window (defect 7.2).
     *
     * <p>{@link #requireOutsideNseCrunch()} only ever checked the <b>start</b> time, so a run
     * begun at 09:35 continued straight through the 09:45 FII/DII fetch and the 10:00 report —
     * precisely the interference the guard exists to prevent, and precisely the failure mode
     * Gotcha 97 describes: the reason for a guard is contention, so the guard has to cover the
     * contention rather than the moment of asking. A multi-symbol loop therefore checks this
     * between symbols and returns what it has, rather than abandoning completed work.
     */
    static boolean shouldStopForNseCrunch() {
        return insideNseCrunch();
    }

    private static final java.time.LocalTime NSE_CRUNCH_START = java.time.LocalTime.of(9, 40);
    private static final java.time.LocalTime NSE_CRUNCH_END = java.time.LocalTime.of(10, 15);

    /**
     * Refusal carrying its own reason. Not {@code ResponseStatusException}:
     * {@code server.error.include-message} defaults to {@code never}, so the caller would get a
     * bare 409 and a guard whose explanation never arrives is the silent failure it exists to
     * prevent (B-049).
     */
    static class BackfillBlockedException extends RuntimeException {
        BackfillBlockedException(String message) { super(message); }
    }

    @ExceptionHandler(BackfillBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleBlocked(BackfillBlockedException e) {
        log.info("Annual-history backfill refused: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 409);
        body.put("error", "Blocked");
        body.put("reason", e.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(body);
    }

    /** Delete a symbol's imported history — for re-importing a corrected file. */
    @DeleteMapping("/history")
    public ResponseEntity<Map<String, Object>> deleteHistory(@RequestParam String symbol) {
        String sym = normalize(symbol);
        long had = historyService.yearsAvailable(sym);
        historyService.deleteHistory(sym);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "symbol", sym, "yearsDeleted", had));
    }

    private static Map<String, Object> toBody(FundamentalsHistoryService.ImportResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", r.yearsParsed() > 0 ? "SUCCESS" : "NO_DATA");
        body.put("symbol", r.symbol());
        body.put("yearsParsed", r.yearsParsed());
        body.put("rowsCreated", r.rowsCreated());
        body.put("rowsUpdated", r.rowsUpdated());
        body.put("rowsLeftAsXbrl", r.rowsSkippedXbrlWins());
        body.put("years", r.years());
        body.put("warnings", r.warnings());
        return body;
    }

    /** Accept RELIANCE or NSE:RELIANCE; store one canonical form. */
    private static String normalize(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase();
        return s.contains(":") ? s : "NSE:" + s;
    }
}
