package com.example.trading.holdings;

import com.example.trading.broker.BrokerClient;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsHistoryEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.HoldingsHistoryRepository;
import com.example.trading.strategy.StrategyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class HoldingsAnalysisService {

    private final BrokerClient brokerClient;
    private final HoldingsRepository holdingsRepository;
    private final HoldingsHistoryRepository holdingsHistoryRepository;
    private final StockValuationService valuationService;
    /** SPEC 46: benchmark closes and cash ride along with the 15:00 snapshot - no cron of their own. */
    private final com.example.trading.portfolio.performance.PortfolioSnapshotService portfolioSnapshotService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Sync holdings from broker to database.
     */
    public void syncHoldingsFromBroker() {
        log.info("Starting holdings sync from broker...");

        try {
            List<Map<String, Object>> brokerHoldings = brokerClient.getHoldings();

            // Safety guard: an empty/failed fetch (e.g. token expiry, transient API error) must NOT
            // be treated as "portfolio is empty" — otherwise reconciliation below would wipe every
            // local holding. Skip the whole sync in that case and keep the last-known holdings.
            if (brokerHoldings == null || brokerHoldings.isEmpty()) {
                log.warn("Broker returned no holdings — skipping sync & reconciliation to avoid wiping local holdings " +
                    "(likely token/API issue, not a genuinely empty portfolio).");
                return;
            }

            log.info("Fetched {} holdings from broker", brokerHoldings.size());

            // Track the symbols actually held at the broker (quantity > 0) so we can reconcile away
            // exited / stale rows afterwards.
            Set<String> liveSymbols = new HashSet<>();
            for (Map<String, Object> holding : brokerHoldings) {
                try {
                    int qty = getInt(holding, "quantity");
                    if (qty <= 0) {
                        // Sold / settling — Kite still lists these with quantity 0 on exit day.
                        // Don't persist them as holdings; reconciliation removes any prior row.
                        log.debug("Skipping zero-quantity broker holding: {}", holding.get("tradingsymbol"));
                        continue;
                    }
                    String storedSymbol = syncSingleHolding(holding);
                    if (storedSymbol != null) {
                        liveSymbols.add(storedSymbol);
                    }
                } catch (Exception e) {
                    log.error("Failed to sync holding {}: {}", holding.get("tradingsymbol"), e.getMessage());
                }
            }

            reconcileExitedHoldings(liveSymbols);
            log.info("Holdings sync completed successfully ({} live holdings)", liveSymbols.size());
        } catch (Exception e) {
            log.error("Failed to sync holdings from broker: {}", e.getMessage(), e);
        }
    }

    /**
     * Removes local holdings the broker no longer reports (fully exited) or that came back with
     * quantity 0 — these are what caused exited/zero-quantity stocks to linger in email reports.
     * Only runs after a successful, non-empty broker fetch (see guard in {@link #syncHoldingsFromBroker}).
     */
    private void reconcileExitedHoldings(Set<String> liveSymbols) {
        List<HoldingsEntity> localHoldings = holdingsRepository.findAll();
        int removed = 0;
        for (HoldingsEntity local : localHoldings) {
            if (!liveSymbols.contains(local.getSymbol())) {
                log.info("Removing exited/stale holding {} (qty was {}, last synced {})",
                    local.getSymbol(), local.getQuantity(), local.getLastSyncedAt());
                holdingsRepository.delete(local);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Reconciliation removed {} exited/stale holdings ({} remain)", removed, liveSymbols.size());
        }
    }

    private String syncSingleHolding(Map<String, Object> brokerHolding) {
        String tradingSymbol = getString(brokerHolding, "tradingsymbol");
        String exchange = getString(brokerHolding, "exchange", "NSE");
        String symbol = exchange + ":" + tradingSymbol;

        // First try to find by exact symbol, then by trading symbol to avoid duplicates
        Optional<HoldingsEntity> existingOpt = holdingsRepository.findBySymbol(symbol);
        if (existingOpt.isEmpty()) {
            // Check if same trading symbol exists with different exchange prefix
            existingOpt = holdingsRepository.findByTradingSymbol(tradingSymbol);
            if (existingOpt.isPresent()) {
                log.debug("Found existing holding for {} with different exchange, updating", tradingSymbol);
            }
        }
        HoldingsEntity entity = existingOpt.orElse(new HoldingsEntity());

        // Basic info
        entity.setSymbol(symbol);
        entity.setTradingSymbol(tradingSymbol);
        entity.setExchange(exchange);
        entity.setIsin(getString(brokerHolding, "isin"));

        // Quantity and prices
        entity.setQuantity(getInt(brokerHolding, "quantity"));
        entity.setAveragePrice(getDouble(brokerHolding, "average_price"));
        entity.setLastPrice(getDouble(brokerHolding, "last_price"));
        entity.setClosePrice(getDouble(brokerHolding, "close_price"));

        // Calculate current value if last_price available
        double lastPrice = entity.getLastPrice();
        if (lastPrice <= 0) {
            lastPrice = entity.getClosePrice();
        }
        entity.setCurrentPrice(lastPrice);

        // P&L calculations
        double investedValue = entity.getQuantity() * entity.getAveragePrice();
        double currentValue = entity.getQuantity() * lastPrice;
        double pnl = currentValue - investedValue;
        double pnlPercent = investedValue > 0 ? (pnl / investedValue) * 100 : 0;

        entity.setInvestedValue(investedValue);
        entity.setCurrentValue(currentValue);
        entity.setPnl(pnl);
        entity.setPnlPercent(pnlPercent);

        // Day change
        double closePrice = entity.getClosePrice();
        if (closePrice > 0 && lastPrice > 0) {
            double dayChange = lastPrice - closePrice;
            double dayChangePercent = (dayChange / closePrice) * 100;
            entity.setDayChange(dayChange);
            entity.setDayChangePercent(dayChangePercent);
        }

        entity.setLastSyncedAt(LocalDateTime.now());

        holdingsRepository.save(entity);
        log.debug("Synced holding: {} (Qty: {}, P&L: {}%)", symbol, entity.getQuantity(), String.format("%.2f", pnlPercent));
        return entity.getSymbol();
    }

    /**
     * Analyze all holdings using technical indicators.
     */
    public void analyzeAllHoldings() {
        log.info("Starting technical analysis for all holdings...");

        List<HoldingsEntity> holdings = holdingsRepository.findActive();
        log.info("Analyzing {} holdings", holdings.size());

        for (HoldingsEntity holding : holdings) {
            try {
                analyzeHolding(holding);
            } catch (Exception e) {
                log.error("Failed to analyze {}: {}", holding.getSymbol(), e.getMessage());
            }
        }

        log.info("Holdings analysis completed");
    }

    /**
     * Analyze a single holding using technical indicators.
     */
    public void analyzeHolding(HoldingsEntity holding) {
        log.debug("Analyzing holding: {}", holding.getSymbol());

        try {
            // Fetch daily historical data (last 250 days for 200 EMA)
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(300);

            List<Map<String, Object>> history = brokerClient.getHistoricalData(
                    holding.getSymbol(),
                    "day",
                    startDate.format(DATE_FORMATTER),
                    endDate.format(DATE_FORMATTER));

            if (history == null || history.size() < 50) {
                log.warn("Insufficient historical data for {}: {} bars", holding.getSymbol(),
                        history != null ? history.size() : 0);
                holding.setAnalysisNotes("Insufficient data for analysis");
                holdingsRepository.save(holding);
                return;
            }

            // Convert to TA4J BarSeries
            BarSeries series = StrategyUtils.convertToBarSeries(holding.getSymbol(), history);

            if (series.getBarCount() < 50) {
                log.warn("Not enough bars for analysis: {}", series.getBarCount());
                return;
            }

            int lastIndex = series.getEndIndex();
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

            // Calculate EMAs
            EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
            EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
            EMAIndicator ema200 = series.getBarCount() >= 200 ? new EMAIndicator(closePrice, 200) : null;

            // RSI
            RSIIndicator rsi = new RSIIndicator(closePrice, 14);

            // ATR for stop loss calculation
            ATRIndicator atr = new ATRIndicator(series, 14);

            // Get current values
            double currentPrice = closePrice.getValue(lastIndex).doubleValue();
            double ema20Value = ema20.getValue(lastIndex).doubleValue();
            double ema50Value = ema50.getValue(lastIndex).doubleValue();
            double ema200Value = ema200 != null ? ema200.getValue(lastIndex).doubleValue() : 0;
            double rsiValue = rsi.getValue(lastIndex).doubleValue();
            double atrValue = atr.getValue(lastIndex).doubleValue();

            // Store indicator values
            holding.setEma20(ema20Value);
            holding.setEma50(ema50Value);
            holding.setEma200(ema200Value);
            holding.setRsi14(rsiValue);
            holding.setAtr14(atrValue);

            // Determine trend
            String trend = determineTrend(currentPrice, ema20Value, ema50Value, ema200Value);
            holding.setTrendDirection(trend);

            // Calculate scores
            int technicalScore = calculateTechnicalScore(currentPrice, ema20Value, ema50Value, ema200Value, rsiValue);
            int momentumScore = calculateMomentumScore(series, lastIndex, rsiValue);
            int overallScore = (technicalScore * 60 + momentumScore * 40) / 100;

            holding.setTechnicalScore(technicalScore);
            holding.setMomentumScore(momentumScore);
            holding.setOverallScore(overallScore);

            // Determine recommendation
            String recommendation = determineRecommendation(overallScore, trend, rsiValue, holding.getPnlPercent());
            holding.setRecommendation(recommendation);

            // Calculate suggested levels
            double suggestedSL = currentPrice - (2 * atrValue);
            double suggestedTarget1 = currentPrice + (2 * atrValue);
            double suggestedTarget2 = currentPrice + (4 * atrValue);

            holding.setSuggestedStopLoss(suggestedSL);
            holding.setSuggestedTarget1(suggestedTarget1);
            holding.setSuggestedTarget2(suggestedTarget2);

            // Calculate Support & Resistance levels from historical price data
            calculateSupportResistance(holding, series);

            // Build analysis notes
            StringBuilder notes = new StringBuilder();
            notes.append(String.format("Trend: %s | ", trend));
            notes.append(String.format("RSI: %.1f | ", rsiValue));
            notes.append(String.format("Price vs EMAs: 20=%.2f, 50=%.2f", ema20Value, ema50Value));
            if (ema200Value > 0) {
                notes.append(String.format(", 200=%.2f", ema200Value));
            }
            holding.setAnalysisNotes(notes.toString());

            holding.setLastAnalyzedAt(LocalDateTime.now());

            // === Valuation Data (PE Comparison) ===
            try {
                fetchValuationData(holding);
            } catch (Exception e) {
                log.debug("Valuation fetch failed for {}: {}", holding.getSymbol(), e.getMessage());
            }

            holdingsRepository.save(holding);

            log.info("Analyzed {}: Score={}, Recommendation={}",
                holding.getSymbol(), overallScore, recommendation);

        } catch (Exception e) {
            log.error("Error analyzing {}: {}", holding.getSymbol(), e.getMessage());
            holding.setAnalysisNotes("Analysis error: " + e.getMessage());
            holdingsRepository.save(holding);
        }
    }

    private String determineTrend(double price, double ema20, double ema50, double ema200) {
        boolean above20 = price > ema20;
        boolean above50 = price > ema50;
        boolean above200 = ema200 <= 0 || price > ema200;
        boolean ema20Above50 = ema20 > ema50;

        if (above20 && above50 && above200 && ema20Above50) {
            return "BULLISH";
        } else if (!above20 && !above50 && (ema200 <= 0 || !above200) && !ema20Above50) {
            return "BEARISH";
        } else {
            return "SIDEWAYS";
        }
    }

    private int calculateTechnicalScore(double price, double ema20, double ema50, double ema200, double rsi) {
        int score = 50; // Base score

        // EMA position (max 30 points)
        if (price > ema20) score += 10;
        if (price > ema50) score += 10;
        if (ema200 > 0 && price > ema200) score += 10;

        // EMA alignment (max 15 points)
        if (ema20 > ema50) score += 10;
        if (ema200 > 0 && ema50 > ema200) score += 5;

        // RSI (max 15 points, penalize extremes)
        if (rsi >= 40 && rsi <= 60) {
            score += 15; // Healthy zone
        } else if (rsi >= 30 && rsi < 40) {
            score += 10; // Slightly oversold - potential buy
        } else if (rsi > 60 && rsi <= 70) {
            score += 5; // Slightly overbought
        } else if (rsi < 30) {
            score += 5; // Heavily oversold - risky
        } else if (rsi > 70) {
            score -= 5; // Overbought - potential correction
        }

        // Penalize if below all EMAs
        if (price < ema20 && price < ema50 && (ema200 <= 0 || price < ema200)) {
            score -= 20;
        }

        return Math.max(0, Math.min(100, score));
    }

    private int calculateMomentumScore(BarSeries series, int lastIndex, double rsi) {
        int score = 50;

        // Recent price action (last 5 days)
        if (lastIndex >= 5) {
            double priceNow = series.getBar(lastIndex).getClosePrice().doubleValue();
            double price5DaysAgo = series.getBar(lastIndex - 5).getClosePrice().doubleValue();
            double change5D = ((priceNow - price5DaysAgo) / price5DaysAgo) * 100;

            if (change5D > 5) score += 20;
            else if (change5D > 2) score += 15;
            else if (change5D > 0) score += 10;
            else if (change5D > -2) score += 5;
            else if (change5D < -5) score -= 15;
            else score -= 5;
        }

        // RSI momentum
        if (rsi > 50 && rsi <= 70) score += 15;
        else if (rsi > 40 && rsi <= 50) score += 5;
        else if (rsi < 30) score -= 10;
        else if (rsi > 80) score -= 10;

        // Volume confirmation (last bar vs average)
        if (lastIndex >= 20) {
            double currentVolume = series.getBar(lastIndex).getVolume().doubleValue();
            double avgVolume = 0;
            for (int i = lastIndex - 20; i < lastIndex; i++) {
                avgVolume += series.getBar(i).getVolume().doubleValue();
            }
            avgVolume /= 20;

            if (currentVolume > avgVolume * 1.5) score += 10;
            else if (currentVolume > avgVolume) score += 5;
            else if (currentVolume < avgVolume * 0.5) score -= 5;
        }

        return Math.max(0, Math.min(100, score));
    }

    private String determineRecommendation(int overallScore, String trend, double rsi, double pnlPercent) {
        // Strong Sell conditions
        if (overallScore < 25) {
            return "STRONG_SELL";
        }

        // Sell conditions
        if (overallScore < 40 || (trend.equals("BEARISH") && rsi > 70)) {
            return "SELL";
        }

        // Strong Buy conditions
        if (overallScore >= 80 && trend.equals("BULLISH") && rsi < 70) {
            return "STRONG_BUY";
        }

        // Buy conditions (accumulate)
        if (overallScore >= 65 && (trend.equals("BULLISH") || trend.equals("SIDEWAYS")) && rsi < 65) {
            return "BUY";
        }

        // Profit booking suggestion for high gains
        if (pnlPercent > 50 && rsi > 70) {
            return "BOOK_PROFIT";
        }

        return "HOLD";
    }

    /**
     * Record daily snapshot for historical tracking.
     */
    public void recordDailySnapshot() {
        log.info("Recording daily holdings snapshot...");
        LocalDate today = LocalDate.now();

        List<HoldingsEntity> holdings = holdingsRepository.findActive();

        for (HoldingsEntity holding : holdings) {
            try {
                // Check if already recorded today
                if (holdingsHistoryRepository.existsBySymbolAndRecordDate(holding.getSymbol(), today)) {
                    log.debug("Snapshot already exists for {} on {}", holding.getSymbol(), today);
                    continue;
                }

                HoldingsHistoryEntity history = HoldingsHistoryEntity.builder()
                        .symbol(holding.getSymbol())
                        .recordDate(today)
                        .quantity(holding.getQuantity())
                        .averagePrice(holding.getAveragePrice())
                        .closePrice(holding.getCurrentPrice())
                        .pnl(holding.getPnl())
                        .pnlPercent(holding.getPnlPercent())
                        .technicalScore(holding.getTechnicalScore())
                        .overallScore(holding.getOverallScore())
                        .recommendation(holding.getRecommendation())
                        .build();

                holdingsHistoryRepository.save(history);
                log.debug("Recorded snapshot for {}", holding.getSymbol());

            } catch (Exception e) {
                log.error("Failed to record snapshot for {}: {}", holding.getSymbol(), e.getMessage());
            }
        }

        log.info("Daily snapshot recording completed");

        // Benchmark closes and available cash, so the portfolio page can compare and size
        // without a broker call on load (SPEC 46.2, 46.4). Never fails the snapshot.
        try {
            portfolioSnapshotService.captureDailyExtras();
        } catch (Exception e) {
            log.warn("Benchmark/cash snapshot failed: {}", e.getMessage());
        }
    }

    /**
     * Calculate support and resistance levels from historical highs/lows.
     * Support 1/2: 20-day and 50-day lows (immediate and strong support)
     * Resistance 1/2: 20-day and 50-day highs (immediate and strong resistance)
     * Pivot Point: (20d High + 20d Low + Close) / 3
     */
    private void calculateSupportResistance(HoldingsEntity holding, BarSeries series) {
        int lastIndex = series.getEndIndex();
        int barCount = series.getBarCount();

        // 20-day high/low
        int period20 = Math.min(20, barCount);
        double high20 = Double.MIN_VALUE;
        double low20 = Double.MAX_VALUE;
        for (int i = lastIndex; i > lastIndex - period20; i--) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            double low = series.getBar(i).getLowPrice().doubleValue();
            if (high > high20) high20 = high;
            if (low < low20) low20 = low;
        }

        // 50-day high/low
        int period50 = Math.min(50, barCount);
        double high50 = Double.MIN_VALUE;
        double low50 = Double.MAX_VALUE;
        for (int i = lastIndex; i > lastIndex - period50; i--) {
            double high = series.getBar(i).getHighPrice().doubleValue();
            double low = series.getBar(i).getLowPrice().doubleValue();
            if (high > high50) high50 = high;
            if (low < low50) low50 = low;
        }

        double closePrice = series.getBar(lastIndex).getClosePrice().doubleValue();
        double pivotPoint = (high20 + low20 + closePrice) / 3.0;

        holding.setPivotPoint(pivotPoint);
        holding.setSupport1(low20);
        holding.setSupport2(low50);
        holding.setResistance1(high20);
        holding.setResistance2(high50);

        log.debug("{} S/R levels: S2={} S1={} PP={} R1={} R2={}",
            holding.getSymbol(),
            String.format("%.2f", low50), String.format("%.2f", low20),
            String.format("%.2f", pivotPoint),
            String.format("%.2f", high20), String.format("%.2f", high50));
    }

    // Utility methods
    private String getString(Map<String, Object> map, String key) {
        return getString(map, key, "");
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private int getInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    /**
     * Fetch valuation data (PE, Industry PE, etc.) for a holding.
     */
    private void fetchValuationData(HoldingsEntity holding) {
        // Skip if recently fetched (within 6 hours - PE values can change during the day),
        // but force re-fetch if any core field (market cap) is missing from a prior attempt.
        if (holding.getLastValuationAt() != null &&
            holding.getMarketCap() != null &&
            holding.getLastValuationAt().isAfter(LocalDateTime.now().minusHours(6))) {
            log.debug("Valuation data still fresh for {}", holding.getSymbol());
            return;
        }

        StockValuationService.ValuationData valuation = valuationService.getValuationData(holding.getSymbol());

        if (valuation == null) {
            log.debug("Could not fetch valuation for {}", holding.getSymbol());
            return;
        }

        // Update holding with valuation data
        holding.setIndustry(valuation.getIndustry());
        holding.setStockPe(valuation.getStockPe());
        holding.setIndustryPe(valuation.getIndustryPe());
        holding.setPeDeviation(valuation.getPeDeviation());
        holding.setMarketCap(valuation.getMarketCap());
        holding.setBookValue(valuation.getBookValue());
        holding.setPriceToBook(valuation.getPriceToBook());
        holding.setEps(valuation.getEps());
        holding.setDividendYield(valuation.getDividendYield());
        holding.setLastValuationAt(LocalDateTime.now());

        log.debug("Updated valuation for {}: PE={}, IndustryPE={}, Deviation={}%",
            holding.getSymbol(),
            valuation.getStockPe(),
            valuation.getIndustryPe(),
            valuation.getPeDeviation() != null ? String.format("%.1f", valuation.getPeDeviation()) : "N/A");
    }

}
