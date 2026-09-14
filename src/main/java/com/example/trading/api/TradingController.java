package com.example.trading.api;

import com.example.trading.config.StockFilterConfig;
import com.example.trading.config.TestModeConfig;
import com.example.trading.marketdata.MarketDataService;
import com.example.trading.broker.BrokerClient;
import com.example.trading.holdings.HoldingsAnalysisService;
import com.example.trading.holdings.HoldingsReportService;
import com.example.trading.scanner.Nifty200WatchlistService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.PositionEntity;
import com.example.trading.persistence.PositionRepository;
import com.example.trading.persistence.TradeEntity;
import com.example.trading.persistence.TradeRepository;
import com.example.trading.risk.RiskConfig;
import com.example.trading.notification.MorningBriefingService;
import com.example.trading.holdings.ExitTimingAlertService;
import com.example.trading.holdings.HoldingsDecayService;
import com.example.trading.holdings.HoldingsBuyTimingService;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for viewing positions, trades, and P&L.
 */
@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
@Slf4j
public class TradingController {

    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;
    private final BrokerClient brokerClient;
    private final MarketDataService marketDataService;
    private final RiskConfig riskConfig;
    private final HoldingsRepository holdingsRepository;
    /** SPEC §6.6: every holdings read path goes through this, so screens cannot disagree. */
    private final com.example.trading.holdings.HoldingsViewDecorator holdingsViewDecorator;
    private final HoldingsAnalysisService holdingsAnalysisService;
    private final HoldingsReportService holdingsReportService;
    private final Nifty200WatchlistService nifty200WatchlistService;
    private final StockFilterConfig stockFilterConfig;
    private final TestModeConfig testModeConfig;
    private final MorningBriefingService morningBriefingService;
    private final ExitTimingAlertService exitTimingAlertService;
    private final HoldingsDecayService holdingsDecayService;
    private final HoldingsBuyTimingService holdingsBuyTimingService;
    private final MarketHoursService marketHoursService;

    /**
     * A per-row refresh costs about two paced Kite calls; it is refused from here to the close so
     * the 15:05-15:28 report jobs keep the shared broker rate limit (B-049, Gotcha 60).
     */
    private static final java.time.LocalTime REFRESH_CRUNCH_START = java.time.LocalTime.of(14, 55);

    /**
     * Get all open positions.
     */
    @GetMapping("/positions/open")
    public ResponseEntity<List<PositionEntity>> getOpenPositions() {
        return ResponseEntity.ok(positionRepository.findByStatus("OPEN"));
    }

    /**
     * Get all closed positions.
     */
    @GetMapping("/positions/closed")
    public ResponseEntity<List<PositionEntity>> getClosedPositions() {
        return ResponseEntity.ok(positionRepository.findByStatus("CLOSED"));
    }

    /**
     * Get all trades.
     */
    @GetMapping("/trades")
    public ResponseEntity<List<TradeEntity>> getAllTrades() {
        return ResponseEntity.ok(tradeRepository.findAll());
    }

    /**
     * Get P&L summary.
     */
    @GetMapping("/pnl/summary")
    public ResponseEntity<Map<String, Object>> getPnLSummary() {
        List<PositionEntity> closedPositions = positionRepository.findByStatus("CLOSED");
        
        double totalRealizedPnL = closedPositions.stream()
            .mapToDouble(PositionEntity::getRealizedPnL)
            .sum();
        
        double totalUnrealizedPnL = positionRepository.findByStatus("OPEN").stream()
            .mapToDouble(PositionEntity::getUnrealizedPnL)
            .sum();
        
        long winningTrades = closedPositions.stream()
            .filter(p -> p.getRealizedPnL() > 0)
            .count();
        
        long losingTrades = closedPositions.stream()
            .filter(p -> p.getRealizedPnL() < 0)
            .count();
        
        double winRate = closedPositions.isEmpty() ? 0.0 : 
            (double) winningTrades / closedPositions.size() * 100;
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalRealizedPnL", totalRealizedPnL);
        summary.put("totalUnrealizedPnL", totalUnrealizedPnL);
        summary.put("netPnL", totalRealizedPnL + totalUnrealizedPnL);
        summary.put("totalTrades", closedPositions.size());
        summary.put("winningTrades", winningTrades);
        summary.put("losingTrades", losingTrades);
        summary.put("winRate", winRate);
        summary.put("openPositions", positionRepository.findByStatus("OPEN").size());
        
        return ResponseEntity.ok(summary);
    }

    /**
     * Test endpoint: Fetch account margins from broker.
     * Use this to verify margin API is working before market opens.
     */
    @GetMapping("/test/margins")
    public ResponseEntity<Map<String, Object>> testGetMargins() {
        try {
            Map<String, Object> margins = brokerClient.getAccountMargins();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Margin API is working correctly");
            response.put("margins", margins);
            response.put("fundValidationEnabled", riskConfig.isEnableFundValidation());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to fetch margins: " + e.getMessage());
            response.put("error", e.getClass().getSimpleName());
            response.put("fundValidationEnabled", riskConfig.isEnableFundValidation());

            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get current risk configuration.
     */
    @GetMapping("/config/risk")
    public ResponseEntity<Map<String, Object>> getRiskConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("totalCapital", riskConfig.getTotalCapital());
        config.put("maxRiskPerTradePct", riskConfig.getMaxRiskPerTradePct());
        config.put("maxDailyLossPct", riskConfig.getMaxDailyLossPct());
        config.put("maxOpenPositions", riskConfig.getMaxOpenPositions());
        config.put("dailyProfitTarget", riskConfig.getDailyProfitTarget());
        config.put("fundValidation", Map.of(
            "enabled", riskConfig.isEnableFundValidation(),
            "marginMultiplier", riskConfig.getMarginMultiplier(),
            "minimumQuantity", riskConfig.getMinimumQuantity()
        ));

        return ResponseEntity.ok(config);
    }

    /**
     * Get positions directly from broker (for debugging/comparison).
     */
    @GetMapping("/positions/broker")
    public ResponseEntity<Map<String, Object>> getBrokerPositions() {
        try {
            List<Map<String, Object>> brokerPositions = brokerClient.getPositions();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("count", brokerPositions.size());
            response.put("positions", brokerPositions);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to fetch broker positions: " + e.getMessage());
            response.put("error", e.getClass().getSimpleName());

            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Clean up duplicate positions in the database.
     * Keeps the most recent position for each symbol and closes duplicates.
     */
    @PostMapping("/positions/cleanup-duplicates")
    public ResponseEntity<Map<String, Object>> cleanupDuplicatePositions() {
        try {
            List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");

            // Group by symbol
            Map<String, List<PositionEntity>> positionsBySymbol = new HashMap<>();
            for (PositionEntity pos : openPositions) {
                positionsBySymbol.computeIfAbsent(pos.getSymbol(), k -> new java.util.ArrayList<>()).add(pos);
            }

            int duplicatesClosed = 0;
            List<String> cleanedSymbols = new java.util.ArrayList<>();

            for (Map.Entry<String, List<PositionEntity>> entry : positionsBySymbol.entrySet()) {
                List<PositionEntity> positions = entry.getValue();
                if (positions.size() > 1) {
                    // Sort by openedAt descending (most recent first)
                    positions.sort((a, b) -> {
                        if (a.getOpenedAt() == null) return 1;
                        if (b.getOpenedAt() == null) return -1;
                        return b.getOpenedAt().compareTo(a.getOpenedAt());
                    });

                    // Close all except the most recent
                    for (int i = 1; i < positions.size(); i++) {
                        PositionEntity duplicate = positions.get(i);
                        duplicate.setStatus("CLOSED");
                        duplicate.setRealizedPnL(0.0);
                        duplicate.setClosedAt(java.time.LocalDateTime.now());
                        duplicate.setLastUpdatedAt(java.time.LocalDateTime.now());
                        positionRepository.save(duplicate);
                        duplicatesClosed++;
                    }
                    cleanedSymbols.add(entry.getKey() + " (" + (positions.size() - 1) + " duplicates)");
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("duplicatesClosed", duplicatesClosed);
            response.put("cleanedSymbols", cleanedSymbols);
            response.put("remainingOpenPositions", positionRepository.findByStatus("OPEN").size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to cleanup duplicates: " + e.getMessage());
            response.put("error", e.getClass().getSimpleName());

            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get all positions (both open and closed) for a specific symbol.
     * Useful for debugging duplicate issues.
     */
    @GetMapping("/positions/by-symbol/{symbol}")
    public ResponseEntity<Map<String, Object>> getPositionsBySymbol(@PathVariable String symbol) {
        // Handle URL-encoded colon (NSE%3ASUNPHARMA -> NSE:SUNPHARMA)
        String decodedSymbol = symbol.replace("%3A", ":");

        List<PositionEntity> allPositions = positionRepository.findAll().stream()
                .filter(p -> p.getSymbol().contains(decodedSymbol))
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("symbol", decodedSymbol);
        response.put("totalPositions", allPositions.size());
        response.put("openPositions", allPositions.stream().filter(p -> "OPEN".equals(p.getStatus())).count());
        response.put("closedPositions", allPositions.stream().filter(p -> "CLOSED".equals(p.getStatus())).count());
        response.put("positions", allPositions);

        return ResponseEntity.ok(response);
    }

    // ==================== HOLDINGS ENDPOINTS ====================

    /**
     * Get all holdings with analysis data.
     */
    @GetMapping("/holdings")
    public ResponseEntity<List<HoldingsEntity>> getAllHoldings() {
        return ResponseEntity.ok(holdingsViewDecorator.decorate(
                holdingsRepository.findActiveOrderByScoreDesc()));
    }

    /**
     * Get holdings portfolio summary.
     */
    @GetMapping("/holdings/summary")
    public ResponseEntity<Map<String, Object>> getHoldingsSummary() {
        Map<String, Object> summary = new HashMap<>();

        List<HoldingsEntity> holdings = holdingsRepository.findAll();
        Double totalInvested = holdingsRepository.getTotalInvestedValue();
        Double totalCurrent = holdingsRepository.getTotalCurrentValue();
        Double totalPnL = holdingsRepository.getTotalPnL();
        Long profitable = holdingsRepository.countProfitableHoldings();
        Long losing = holdingsRepository.countLossHoldings();

        double pnlPercent = totalInvested != null && totalInvested > 0
                ? ((totalCurrent - totalInvested) / totalInvested) * 100 : 0;

        summary.put("totalHoldings", holdings.size());
        summary.put("totalInvested", totalInvested != null ? totalInvested : 0);
        summary.put("currentValue", totalCurrent != null ? totalCurrent : 0);
        summary.put("totalPnL", totalPnL != null ? totalPnL : 0);
        summary.put("pnlPercent", pnlPercent);
        summary.put("profitableCount", profitable != null ? profitable : 0);
        summary.put("losingCount", losing != null ? losing : 0);

        // Count by recommendation
        long strongBuy = holdings.stream().filter(h -> "STRONG_BUY".equals(h.getRecommendation())).count();
        long buy = holdings.stream().filter(h -> "BUY".equals(h.getRecommendation())).count();
        long hold = holdings.stream().filter(h -> "HOLD".equals(h.getRecommendation())).count();
        long sell = holdings.stream().filter(h -> "SELL".equals(h.getRecommendation())).count();
        long strongSell = holdings.stream().filter(h -> "STRONG_SELL".equals(h.getRecommendation())).count();

        Map<String, Long> recommendations = new HashMap<>();
        recommendations.put("STRONG_BUY", strongBuy);
        recommendations.put("BUY", buy);
        recommendations.put("HOLD", hold);
        recommendations.put("SELL", sell);
        recommendations.put("STRONG_SELL", strongSell);
        summary.put("recommendations", recommendations);

        return ResponseEntity.ok(summary);
    }

    /**
     * Get exit candidates (stocks recommended to sell).
     */
    @GetMapping("/holdings/exit-candidates")
    public ResponseEntity<List<HoldingsEntity>> getExitCandidates() {
        return ResponseEntity.ok(holdingsViewDecorator.decorate(
                holdingsRepository.findExitCandidates()));
    }

    /**
     * Get accumulate candidates (stocks recommended to buy more).
     */
    @GetMapping("/holdings/accumulate-candidates")
    public ResponseEntity<List<HoldingsEntity>> getAccumulateCandidates() {
        return ResponseEntity.ok(holdingsViewDecorator.decorate(
                holdingsRepository.findAccumulateCandidates()));
    }

    /**
     * Get holdings by minimum score.
     */
    @GetMapping("/holdings/by-score")
    public ResponseEntity<List<HoldingsEntity>> getHoldingsByScore(
            @RequestParam(defaultValue = "50") Integer minScore) {
        return ResponseEntity.ok(holdingsViewDecorator.decorate(
                holdingsRepository.findByMinScore(minScore)));
    }

    /**
     * Manually trigger holdings sync from broker.
     */
    @PostMapping("/holdings/sync")
    public ResponseEntity<Map<String, Object>> syncHoldings() {
        try {
            holdingsAnalysisService.syncHoldingsFromBroker();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Holdings synced from broker");
            response.put("count", holdingsRepository.count());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to sync holdings: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Manually trigger holdings analysis.
     */
    @PostMapping("/holdings/analyze")
    public ResponseEntity<Map<String, Object>> analyzeHoldings() {
        try {
            holdingsAnalysisService.analyzeAllHoldings();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Holdings analysis completed");
            response.put("count", holdingsRepository.count());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to analyze holdings: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Trigger full holdings sync and analysis.
     */
    @PostMapping("/holdings/refresh")
    public ResponseEntity<Map<String, Object>> refreshHoldings() {
        try {
            // Sync from broker
            holdingsAnalysisService.syncHoldingsFromBroker();

            // Run analysis
            holdingsAnalysisService.analyzeAllHoldings();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Holdings refreshed and analyzed");
            response.put("count", holdingsRepository.count());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to refresh holdings: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Send holdings report email manually.
     */
    @PostMapping("/holdings/send-report")
    public ResponseEntity<Map<String, Object>> sendHoldingsReport() {
        try {
            holdingsReportService.sendDailyHoldingsReport();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "Holdings report email sent");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ERROR");
            response.put("message", "Failed to send report: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Get single holding details by symbol.
     */
    @GetMapping("/holdings/{symbol}")
    public ResponseEntity<?> getHoldingBySymbol(@PathVariable String symbol) {
        // Decorated like every other read path, so the stock page cannot show a different
        // signal from the portfolio table (SPEC §6.6).
        return holdingsRepository.findByTradingSymbol(symbol)
                .or(() -> holdingsRepository.findBySymbol(symbol))
                .map(h -> ResponseEntity.ok((Object) holdingsViewDecorator.decorateOne(h)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== STOCK FILTER / BLACKLIST ENDPOINTS ====================

    /**
     * Get current stock filter configuration including blacklist.
     */
    @GetMapping("/filter/config")
    public ResponseEntity<Map<String, Object>> getStockFilterConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", stockFilterConfig.isEnabled());
        config.put("blacklist", stockFilterConfig.getBlacklist());
        config.put("blacklistCount", stockFilterConfig.getBlacklist() != null ? stockFilterConfig.getBlacklist().size() : 0);
        config.put("minPrice", stockFilterConfig.getMinPrice());
        config.put("maxPrice", stockFilterConfig.getMaxPrice());
        config.put("minAvgVolumeLakhs", stockFilterConfig.getMinAvgVolumeLakhs());
        config.put("activeWatchlistSize", nifty200WatchlistService.getWatchlistSize());
        config.put("totalWatchlistSize", nifty200WatchlistService.getAllNifty200SymbolsUnfiltered().size());

        return ResponseEntity.ok(config);
    }

    /**
     * Get list of blacklisted stocks.
     */
    @GetMapping("/filter/blacklist")
    public ResponseEntity<Map<String, Object>> getBlacklist() {
        Map<String, Object> response = new HashMap<>();
        response.put("blacklist", nifty200WatchlistService.getBlacklistedSymbols());
        response.put("count", nifty200WatchlistService.getBlacklistCount());

        return ResponseEntity.ok(response);
    }

    /**
     * Check if a specific stock is blacklisted.
     */
    @GetMapping("/filter/check/{symbol}")
    public ResponseEntity<Map<String, Object>> checkStockFilter(@PathVariable String symbol) {
        String decodedSymbol = symbol.replace("%3A", ":");

        Map<String, Object> response = new HashMap<>();
        response.put("symbol", decodedSymbol);
        response.put("isBlacklisted", stockFilterConfig.isBlacklisted(decodedSymbol));
        response.put("isInWatchlist", nifty200WatchlistService.isInWatchlist(decodedSymbol));
        response.put("isTradeable", nifty200WatchlistService.isTradeable(decodedSymbol));
        response.put("category", nifty200WatchlistService.getCategory(decodedSymbol));

        return ResponseEntity.ok(response);
    }

    /**
     * Get watchlist info including blacklist impact.
     */
    @GetMapping("/filter/watchlist-summary")
    public ResponseEntity<Map<String, Object>> getWatchlistSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalUnfiltered", nifty200WatchlistService.getAllNifty200SymbolsUnfiltered().size());
        summary.put("blacklistedCount", nifty200WatchlistService.getBlacklistCount());
        summary.put("activeWatchlistSize", nifty200WatchlistService.getWatchlistSize());
        summary.put("nifty50Active", nifty200WatchlistService.getNifty50Symbols().size());
        summary.put("niftyNext50Active", nifty200WatchlistService.getNiftyNext50Symbols().size());
        summary.put("midcap100Active", nifty200WatchlistService.getMidcap100Symbols().size());
        summary.put("blacklistedSymbols", nifty200WatchlistService.getBlacklistedSymbols());

        return ResponseEntity.ok(summary);
    }

    // ==================== TEST MODE ENDPOINTS ====================

    /**
     * Get current test mode configuration.
     */
    @GetMapping("/test-mode/config")
    public ResponseEntity<Map<String, Object>> getTestModeConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", testModeConfig.isEnabled());
        config.put("testStocks", testModeConfig.getTestStocks());
        config.put("testStocksCount", testModeConfig.getTestStocks() != null ? testModeConfig.getTestStocks().size() : 0);
        config.put("summary", testModeConfig.getTestStocksSummary());

        return ResponseEntity.ok(config);
    }

    /**
     * Manually trigger morning briefing email.
     */
    @PostMapping("/morning-briefing")
    public ResponseEntity<Map<String, Object>> sendMorningBriefing() {
        Map<String, Object> result = new HashMap<>();
        result.put("timestamp", java.time.LocalDateTime.now().toString());

        try {
            morningBriefingService.sendMorningBriefing();
            result.put("status", "SUCCESS");
            result.put("message", "Morning briefing email sent successfully");
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Manually trigger exit timing alerts check.
     */
    @PostMapping("/holdings/exit-alerts")
    public ResponseEntity<Map<String, Object>> checkExitAlerts() {
        Map<String, Object> result = new HashMap<>();
        result.put("timestamp", java.time.LocalDateTime.now().toString());

        try {
            exitTimingAlertService.checkExitConditions();
            result.put("status", "SUCCESS");
            result.put("message", "Exit timing alerts check completed");
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Score-decay check across all current holdings (SPEC §6).
     * Returns each holding's verdict (BROKEN / DECAYING / WATCH / INTACT / STALE / NO_DATA)
     * with 30d and 60d composite-score deltas.
     */
    @GetMapping("/holdings/decay")
    public ResponseEntity<List<HoldingsDecayService.DecayAlert>> holdingsDecay() {
        return ResponseEntity.ok(holdingsDecayService.detectDecayForAllHoldings());
    }

    /**
     * "Is it still a good time to buy more?" per holding (SPEC §6.5), keyed by the holding's own
     * symbol. Shares the watchlist's rule table and defers to the watchlist's answer for a stock
     * the investor also tracks, so one stock never gets two answers (Gotcha 81).
     *
     * <p>DB-only — repository reads and arithmetic, no broker or NSE call. Safe on a page load,
     * which for a {@code get()} is a hand-verified property rather than an enforced one
     * (Gotcha 39).
     */
    @GetMapping("/holdings/buy-timing")
    public ResponseEntity<Map<String, HoldingsBuyTimingService.HoldingBuyTiming>> holdingsBuyTiming() {
        return ResponseEntity.ok(holdingsBuyTimingService.buildAll());
    }

    /**
     * Re-analyse one holding now: fresh price, technicals and ML for that stock only.
     * Costs roughly two paced Kite calls. Refused from 14:55 with the reason in the body.
     */
    @PostMapping("/holdings/refresh-one")
    public ResponseEntity<Map<String, Object>> refreshOneHolding(@RequestParam String symbol) {
        requireOutsideAfternoonCrunch();

        HoldingsEntity holding = holdingsRepository.findBySymbol(symbol)
                .or(() -> holdingsRepository.findByTradingSymbol(symbol))
                .orElse(null);
        if (holding == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("status", 404);
            body.put("error", "Not held");
            body.put("reason", "No active holding for " + symbol + ". Only stocks you own can be refreshed here.");
            return ResponseEntity.status(404).body(body);
        }

        holdingsAnalysisService.analyzeHolding(holding);

        HoldingsEntity refreshed = holdingsRepository.findBySymbol(holding.getSymbol()).orElse(holding);
        Map<String, Object> body = new HashMap<>();
        body.put("status", "SUCCESS");
        body.put("holding", refreshed);
        body.put("buyTiming", holdingsBuyTimingService.evaluate(refreshed, Map.of()));
        return ResponseEntity.ok(body);
    }

    private void requireOutsideAfternoonCrunch() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(marketHoursService.getMarketZone());
        if (now.toLocalTime().isBefore(REFRESH_CRUNCH_START) || !marketHoursService.isMarketOpen()) return;
        throw new RefreshBlockedException(String.format(
                "Refreshing a holding makes live broker calls and is blocked between %s and market "
                        + "close: the 15:05-15:28 report jobs need the shared Kite rate limit and "
                        + "abort silently at 15:30. Try again after the close, or before %s IST.",
                REFRESH_CRUNCH_START, REFRESH_CRUNCH_START));
    }

    /**
     * Refusal carrying its own reason. Deliberately not {@code ResponseStatusException}:
     * {@code server.error.include-message} defaults to {@code never}, so the caller would get a
     * bare 409 and a guard whose explanation never arrives is the silent failure it exists to
     * prevent (B-049, Gotcha 60).
     */
    static class RefreshBlockedException extends RuntimeException {
        RefreshBlockedException(String message) { super(message); }
    }

    @ExceptionHandler(RefreshBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleRefreshBlocked(RefreshBlockedException e) {
        log.info("Holding refresh refused: {}", e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("status", 409);
        body.put("error", "Blocked");
        body.put("reason", e.getMessage());
        return ResponseEntity.status(409).body(body);
    }

    // ========== DYNAMIC WATCHLIST ENDPOINTS ==========

}
