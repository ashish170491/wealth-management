package com.example.trading.watchlist;

import com.example.trading.scheduler.MarketHoursService;
import com.example.trading.watchlist.WatchlistTrackingService.WatchlistException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for the watchlist (SPEC §37, §16).
 *
 * <p>Page-load safe (DB-only): {@code GET /items}, {@code GET /series}. Button-only, Kite-calling:
 * {@code POST /items} (add) and {@code POST /items/refresh} — both refused from 14:55 with a 409
 * that carries its reason (B-049). {@code symbol} is always a query parameter, never a path
 * variable (SPEC §27.3). The former {@code /symbols/{symbol}} in-memory add/remove is gone.
 */
@RestController
@RequestMapping("/api/watchlist")
@Slf4j
@RequiredArgsConstructor
public class WatchlistController {

    /** From here the 15:00–15:28 report jobs need the shared Kite rate limit (B-014, B-049). */
    private static final LocalTime CRUNCH_START = LocalTime.of(14, 55);

    private final WatchlistAnalysisService analysisService;
    private final WatchlistTrackingService trackingService;
    private final WatchlistScheduler scheduler;
    private final WatchlistConfig config;
    private final WatchlistRepository repository;
    private final MarketHoursService marketHoursService;

    // ------------------------------------------------------------- DB-only reads

    /** The dashboard's list. DB-only, safe on page load. */
    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(defaultValue = "false") boolean includeRemoved) {
        List<WatchlistItemView> items = trackingService.buildView(includeRemoved);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.stream().filter(WatchlistItemView::active).count());
        out.put("items", items);
        return out;
    }

    /** One row, removed or not. DB-only. */
    @GetMapping("/item")
    public ResponseEntity<WatchlistItemView> item(@RequestParam String symbol) {
        return trackingService.viewFor(WatchlistSymbols.normalise(symbol))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Legacy raw rows (analysis fields only). Kept for back-compat; prefer {@code /items}. */
    @GetMapping
    public ResponseEntity<List<WatchlistEntity>> getAllWatchlistStocks() {
        return ResponseEntity.ok(analysisService.getAllWatchlistStocks());
    }

    @GetMapping("/buy-signals")
    public ResponseEntity<List<WatchlistEntity>> getBuySignals() {
        return ResponseEntity.ok(analysisService.getStocksWithBuySignals());
    }

    @GetMapping("/strong-buy-signals")
    public ResponseEntity<List<WatchlistEntity>> getStrongBuySignals() {
        return ResponseEntity.ok(analysisService.getStocksWithStrongBuySignals());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> configMap = new LinkedHashMap<>();
        configMap.put("enabled", config.isEnabled());
        configMap.put("seedSymbols", config.getSymbols());
        configMap.put("activeCount", repository.findActiveOrderByAddedOn().size());
        configMap.put("scheduler_enabled", config.getScheduler().isEnabled());
        configMap.put("analysis_cron", config.getScheduler().getAnalysisCron());
        configMap.put("verdict", config.getVerdict());
        return ResponseEntity.ok(configMap);
    }

    // ------------------------------------------------------------ button writes

    public record AddRequest(String symbol, String note) {}
    public record NoteRequest(String note) {}

    /** Add a stock: prices it live and runs the technical analysis (~4 paced Kite calls). */
    @PostMapping("/items")
    public WatchlistItemView add(@RequestBody AddRequest req) {
        requireOutsideAfternoonCrunch("add");
        log.info("API: watchlist add {}", req.symbol());
        return trackingService.add(req.symbol(), req.note());
    }

    /** Re-analyse one stock now; {@code quality=true} also computes an ad-hoc composite (5–20 s). */
    @PostMapping("/items/refresh")
    public WatchlistItemView refresh(@RequestParam String symbol,
                                     @RequestParam(defaultValue = "false") boolean quality) {
        requireOutsideAfternoonCrunch("refresh");
        log.info("API: watchlist refresh {} (quality={})", symbol, quality);
        return trackingService.refresh(symbol, quality);
    }

    /** Soft-delete — the row and its history are kept. DB-only. */
    @PostMapping("/items/remove")
    public WatchlistItemView remove(@RequestParam String symbol) {
        log.info("API: watchlist remove {}", symbol);
        return trackingService.remove(symbol);
    }

    @PostMapping("/items/note")
    public WatchlistItemView note(@RequestParam String symbol, @RequestBody NoteRequest req) {
        return trackingService.updateNote(symbol, req.note());
    }

    /** Re-analyse every active symbol. Never from the UI. */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> triggerAnalysis() {
        requireOutsideAfternoonCrunch("full analysis");
        log.info("API: Triggering manual watchlist analysis");
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        analysisService.analyzeAllWatchlistStocks();
        result.put("success", true);
        result.put("message", "Watchlist analysis completed");
        result.put("duration_ms", System.currentTimeMillis() - startTime);
        result.put("stocks_analyzed", analysisService.getAllWatchlistStocks().size());
        return ResponseEntity.ok(result);
    }

    /** Re-analyse and email. Never from the UI. */
    @PostMapping("/analyze-and-report")
    public ResponseEntity<Map<String, Object>> triggerAnalysisAndReport() {
        requireOutsideAfternoonCrunch("analysis and report");
        log.info("API: Triggering manual watchlist analysis and report");
        Map<String, Object> result = new HashMap<>();
        long startTime = System.currentTimeMillis();
        scheduler.triggerManualAnalysis();
        result.put("success", true);
        result.put("message", "Watchlist analysis completed and report sent");
        result.put("duration_ms", System.currentTimeMillis() - startTime);
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------ guards

    private void requireOutsideAfternoonCrunch(String what) {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        if (now.toLocalTime().isBefore(CRUNCH_START) || !marketHoursService.isMarketOpen()) return;
        throw new WatchlistException(409, String.format(
                "Watchlist %s makes live Kite calls and is blocked between %s and the close: the 15:05–15:28 "
                        + "report jobs need the shared broker rate limit and abort silently at 15:30. "
                        + "Try again after 15:30, or before %s IST.", what, CRUNCH_START, CRUNCH_START));
    }

    /**
     * Refusals carry their reason. Not {@code ResponseStatusException}: {@code server.error.include-message}
     * defaults to {@code never}, so the reason would never reach the caller (Gotcha 60).
     */
    @ExceptionHandler(WatchlistException.class)
    public ResponseEntity<Map<String, Object>> handleRefusal(WatchlistException e) {
        log.info("Watchlist request refused ({}): {}", e.status(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", e.status());
        body.put("error", e.status() == 409 ? "Blocked" : e.status() == 404 ? "Not found" : "Rejected");
        body.put("reason", e.getMessage());
        return ResponseEntity.status(HttpStatus.valueOf(e.status())).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadSymbol(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 400);
        body.put("error", "Rejected");
        body.put("reason", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
