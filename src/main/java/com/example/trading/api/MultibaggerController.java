package com.example.trading.api;

import com.example.trading.multibagger.MultibaggerConfig;
import com.example.trading.multibagger.MultibaggerReportService;
import com.example.trading.multibagger.MultibaggerScore;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for Multibagger Screening & Analysis.
 */
@RestController
@RequestMapping("/api/multibagger")
@RequiredArgsConstructor
public class MultibaggerController {

    private final MultibaggerScreenerService screenerService;
    private final MultibaggerReportService reportService;
    private final MultibaggerScoreRepository scoreRepository;
    private final MultibaggerConfig config;
    private final com.example.trading.scanner.Nifty200WatchlistService nseWatchlist;

    /**
     * Get latest screening results.
     * GET /api/multibagger/scores
     */
    @GetMapping("/scores")
    public ResponseEntity<List<MultibaggerScore>> getLatestScores() {
        List<MultibaggerScore> scores = screenerService.getLatestScores();
        if (scores.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(scores);
    }

    /**
     * Get top multibagger candidates (score >= threshold).
     * GET /api/multibagger/candidates
     */
    @GetMapping("/candidates")
    public ResponseEntity<List<MultibaggerScore>> getTopCandidates() {
        return ResponseEntity.ok(screenerService.getTopCandidates());
    }

    /**
     * Get multibagger scores for holdings only.
     * GET /api/multibagger/holdings
     */
    @GetMapping("/holdings")
    public ResponseEntity<List<MultibaggerScore>> getHoldingsScores() {
        return ResponseEntity.ok(screenerService.getHoldingsScores());
    }

    /**
     * Run full screening now (manual trigger).
     * POST /api/multibagger/screen
     */
    @PostMapping("/screen")
    public ResponseEntity<Map<String, Object>> runScreening() {
        Map<String, Object> response = new HashMap<>();
        try {
            List<MultibaggerScore> scores = screenerService.runFullScreening();
            long candidates = scores.stream()
                    .filter(s -> s.getCompositeScore() >= config.getMinScoreForCandidate())
                    .count();

            response.put("status", "SUCCESS");
            response.put("totalScreened", scores.size());
            response.put("candidates", candidates);
            response.put("topScore", scores.isEmpty() ? 0 : scores.get(0).getCompositeScore());
            response.put("topStock", scores.isEmpty() ? "N/A" : scores.get(0).getTradingSymbol());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Screen a single stock.
     * GET /api/multibagger/screen/{symbol}
     * Example: /api/multibagger/screen/NSE:RELIANCE
     */
    @GetMapping("/screen/{symbol}")
    public ResponseEntity<Object> screenSingleStock(@PathVariable String symbol) {
        try {
            MultibaggerScore score = screenerService.screenSingleStock(symbol);
            if (score == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "ERROR");
                error.put("message", "Could not screen " + symbol + " (insufficient data or invalid symbol)");
                return ResponseEntity.badRequest().body(error);
            }
            return ResponseEntity.ok(score);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "ERROR");
            error.put("message", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * Screen only stocks in a specific market-cap tier. Tier strings are defined in
     * {@link com.example.trading.scanner.Nifty200WatchlistService}: {@code LARGE_ONLY},
     * {@code LARGE_MID}, {@code LARGE_MID_SMALL}, {@code ALL}.
     * GET /api/multibagger/screen/tier/{tier}
     */
    @GetMapping("/screen/tier/{tier}")
    public ResponseEntity<Map<String, Object>> screenTier(@PathVariable String tier) {
        Map<String, Object> response = new HashMap<>();
        try {
            List<String> symbols = nseWatchlist.getSymbolsByTier(tier.toUpperCase());
            int ok = 0;
            int failed = 0;
            for (String s : symbols) {
                try {
                    if (screenerService.screenSingleStock(s) != null) ok++; else failed++;
                } catch (Exception ignored) { failed++; }
            }
            response.put("status", "SUCCESS");
            response.put("tier", tier.toUpperCase());
            response.put("symbolsInTier", symbols.size());
            response.put("scored", ok);
            response.put("failed", failed);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Send multibagger report email now (manual trigger).
     * POST /api/multibagger/report
     */
    @PostMapping("/report")
    public ResponseEntity<Map<String, Object>> sendReport() {
        Map<String, Object> response = new HashMap<>();
        try {
            reportService.sendWeeklyReport();
            response.put("status", "SUCCESS");
            response.put("message", "Multibagger report email sent");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("message", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get score history/trend for a specific stock.
     * GET /api/multibagger/trend/{symbol}?days=30
     */
    @GetMapping("/trend/{symbol}")
    public ResponseEntity<List<MultibaggerScoreEntity>> getScoreTrend(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(reportService.getScoreTrend(symbol, days));
    }

    /**
     * Get historical screening results for a specific date.
     * GET /api/multibagger/history?date=2026-03-15
     */
    @GetMapping("/history")
    public ResponseEntity<List<MultibaggerScoreEntity>> getHistoricalScores(
            @RequestParam(required = false) String date) {
        LocalDate screenDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        return ResponseEntity.ok(scoreRepository.findByScreeningDateOrderByCompositeScoreDesc(screenDate));
    }

    /**
     * Under-the-radar candidates from the most recent screening run that actually has rows
     * (SPEC §12.10). DB-only and fast — safe for a dashboard page load.
     *
     * <p>Walks back through {@code findScreeningDates()} rather than defaulting to today:
     * today has no rows until the 14:00 run, and the in-memory cache is empty after the
     * daily restart (Gotcha 20).
     *
     * GET /api/multibagger/under-radar
     */
    @GetMapping("/under-radar")
    public ResponseEntity<Map<String, Object>> getUnderRadar(
            @RequestParam(required = false) Integer minUnderDiscovery) {
        int floor = minUnderDiscovery != null ? minUnderDiscovery : config.getUnderRadarMinScore();
        List<LocalDate> dates = scoreRepository.findScreeningDates();

        Map<String, Object> out = new LinkedHashMap<>();
        if (dates == null || dates.isEmpty()) {
            out.put("screeningDate", null);
            out.put("stocks", List.of());
            out.put("note", "No screening has been persisted yet.");
            return ResponseEntity.ok(out);
        }

        LocalDate latest = dates.get(0);
        List<MultibaggerScoreEntity> rows = scoreRepository
                .findByScreeningDateOrderByCompositeScoreDesc(latest).stream()
                .filter(s -> s.getUnderDiscoveryScore() != null)
                .filter(s -> s.getCompositeScore() >= 65)
                .filter(s -> s.getUnderDiscoveryScore() >= floor)
                .sorted(Comparator.comparing(MultibaggerScoreEntity::getUnderDiscoveryScore).reversed())
                .collect(Collectors.toList());

        long notMeasured = scoreRepository.findByScreeningDateOrderByCompositeScoreDesc(latest).stream()
                .filter(s -> s.getUnderDiscoveryScore() == null).count();

        out.put("screeningDate", latest);
        out.put("minUnderDiscovery", floor);
        out.put("stocks", rows);
        // Surfaced rather than hidden: a stock with no under-discovery score was not
        // measured (quality gate, or missing shareholding data) — it is not a zero.
        out.put("notMeasured", notMeasured);
        return ResponseEntity.ok(out);
    }

    /**
     * Get candidates filtered by market cap category.
     * GET /api/multibagger/by-cap?category=SMALL_CAP
     */
    @GetMapping("/by-cap")
    public ResponseEntity<List<MultibaggerScore>> getByMarketCap(
            @RequestParam(defaultValue = "SMALL_CAP") String category) {
        List<MultibaggerScore> filtered = screenerService.getLatestScores().stream()
                .filter(s -> category.equals(s.getMarketCapCategory()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(filtered);
    }
}
