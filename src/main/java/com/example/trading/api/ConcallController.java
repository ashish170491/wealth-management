package com.example.trading.api;

import com.example.trading.concall.ConcallAnalysisService;
import com.example.trading.concall.GuidanceItemEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Earnings-call transcripts and the management-guidance ledger (SPEC.md §34).
 *
 * <p><b>Page-load safety.</b> {@code /credibility} and {@code /ledger} are DB-only.
 * {@code /analyze} downloads a PDF and calls the AI provider — it is a POST precisely so it
 * cannot be reached by a page load (SPEC §27.4), and it is not on the UI's on-demand
 * allowlist.
 */
@Slf4j
@RestController
@RequestMapping("/api/concall")
@RequiredArgsConstructor
public class ConcallController {

    private final ConcallAnalysisService concallService;

    /**
     * Read the latest transcript for a stock and summarise it.
     *
     * @param record when true, also files each concrete guidance line in the ledger
     * @param quarter label to file promises under, e.g. {@code Q1FY26} (required with record=true)
     */
    @PostMapping("/analyze/{symbol}")
    public ResponseEntity<ConcallAnalysisService.ConcallResult> analyze(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "false") boolean record,
            @RequestParam(required = false) String quarter) {
        String sym = normalize(symbol);
        log.info("API: Concall analysis requested for {} (record={})", sym, record);
        return ResponseEntity.ok(record && quarter != null
                ? concallService.analyzeAndRecord(sym, quarter)
                : concallService.analyzeLatest(sym));
    }

    /** Measured management delivery record. DB-only, dashboard-safe. */
    @GetMapping("/credibility/{symbol}")
    public ResponseEntity<ConcallAnalysisService.CredibilityResult> credibility(@PathVariable String symbol) {
        return ResponseEntity.ok(concallService.credibility(normalize(symbol)));
    }

    /** Every promise recorded for a stock, newest first. DB-only. */
    @GetMapping("/ledger/{symbol}")
    public ResponseEntity<Map<String, Object>> ledger(@PathVariable String symbol) {
        String sym = normalize(symbol);
        List<GuidanceItemEntity> items = concallService.ledger(sym);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "SUCCESS");
        body.put("symbol", sym);
        body.put("count", items.size());
        body.put("minResolvedForCredibility", ConcallAnalysisService.MIN_RESOLVED_FOR_CREDIBILITY);
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /**
     * Record what actually happened for one guidance item.
     *
     * <p>Manual by design: deciding whether "margins will improve" was met is a judgement
     * a human makes from the results, and automating it would fill the ledger with
     * confident nonsense.
     */
    @PostMapping("/resolve/{id}")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable Long id,
                                                       @RequestParam boolean met,
                                                       @RequestParam(required = false) String actual) {
        boolean ok = concallService.resolve(id, actual, met);
        return ok
                ? ResponseEntity.ok(Map.of("status", "SUCCESS", "id", id, "met", met))
                : ResponseEntity.badRequest().body(Map.of("status", "NOT_FOUND", "id", id));
    }

    private static String normalize(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim().toUpperCase();
        return s.contains(":") ? s : "NSE:" + s;
    }
}
