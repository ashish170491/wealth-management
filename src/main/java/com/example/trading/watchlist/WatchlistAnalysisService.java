package com.example.trading.watchlist;

import com.example.trading.broker.BrokerClient;
import com.example.trading.holdings.StockValuationService;
import com.example.trading.strategy.StrategyUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service to analyze watchlist stocks.
 * Performs technical analysis and generates entry signals.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WatchlistAnalysisService {

    private final BrokerClient brokerClient;
    private final WatchlistRepository watchlistRepository;
    private final WatchlistConfig watchlistConfig;
    private final StockValuationService stockValuationService;
    private final com.example.trading.persistence.HoldingsRepository holdingsRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Analyze all stocks in the watchlist.
     */
    public void analyzeAllWatchlistStocks() {
        if (!watchlistConfig.isEnabled()) {
            log.debug("Watchlist feature is disabled");
            return;
        }

        // SPEC §37: the table is the source of truth. application.yml is only a seed list.
        List<String> symbols = watchlistRepository.findActiveOrderByAddedOn().stream()
                .map(WatchlistEntity::getSymbol).toList();
        if (symbols.isEmpty()) {
            log.info("No active symbols on the watchlist");
            return;
        }

        log.info("=== Starting watchlist analysis for {} symbols ===", symbols.size());

        int successCount = 0;
        int failCount = 0;

        for (String symbol : symbols) {
            try {
                analyzeSymbol(symbol);
                successCount++;
            } catch (Exception e) {
                log.error("Failed to analyze watchlist symbol {}: {}", symbol, e.getMessage());
                failCount++;
            }
        }

        log.info("=== Watchlist analysis complete: {} success, {} failed ===", successCount, failCount);
    }

    /** What one analysis run produced: the saved row and the daily candles it was computed from. */
    public record AnalysisOutcome(WatchlistEntity entity, List<Map<String, Object>> history) {}

    /**
     * Analyze a single symbol. Only analysis fields are written — the SPEC §37 membership
     * fields (addedOn, priceAtAdd, active, …) are never touched here, because this method
     * overwrites on every run and would otherwise erase what the investor recorded.
     *
     * @return the saved row plus the candles fetched, so a caller can backfill daily snapshots
     *         without a second Kite call
     */
    public AnalysisOutcome analyzeSymbol(String symbol) {
        log.debug("Analyzing watchlist symbol: {}", symbol);

        try {
            // Get or create entity
            WatchlistEntity entity = watchlistRepository.findBySymbol(symbol)
                    .orElse(new WatchlistEntity());

            entity.setSymbol(symbol);
            entity.setTradingSymbol(extractTradingSymbol(symbol));
            entity.setExchange(extractExchange(symbol));

            // Fetch current quote
            fetchCurrentQuote(entity);

            // Fetch historical data and analyze
            List<Map<String, Object>> history = analyzeWithHistoricalData(entity);

            // Fetch fundamental data
            enrichWithFundamentals(entity);

            // A watched stock the investor now also owns is tagged, not dropped.
            entity.setInHoldings(holdingsRepository.findBySymbol(symbol)
                    .map(h -> h.getQuantity() > 0).orElse(false));

            // Save
            entity.setLastAnalyzedAt(LocalDateTime.now());
            WatchlistEntity saved = watchlistRepository.save(entity);

            log.info("Analyzed {}: Score={}, Signal={}, RSI={}, Entry={}",
                    symbol,
                    entity.getOverallScore(),
                    entity.getEntrySignal(),
                    String.format("%.1f", entity.getRsi14() != null ? entity.getRsi14() : 0.0),
                    entity.getSuggestedEntry() != null ? String.format("%.2f", entity.getSuggestedEntry()) : "N/A");

            return new AnalysisOutcome(saved, history == null ? List.of() : history);

        } catch (Exception e) {
            log.error("Error analyzing {}: {}", symbol, e.getMessage());
            throw e;
        }
    }

    private void fetchCurrentQuote(WatchlistEntity entity) {
        try {
            Map<String, Object> raw = brokerClient.getQuote(entity.getSymbol());
            // Kite returns {status, data: {"NSE:X": {last_price, ohlc, volume}}}. Reading last_price
            // off the wrapper yielded null and OVERWROTE a real price on every run — every watchlist
            // row carried currentPrice=null for months (B-063). Unwrap, and never null-out a real price.
            Map<String, Object> quote = unwrapQuote(raw, entity.getSymbol());
            if (quote == null) {
                log.warn("No quote payload for {} — keeping the previous price {}", entity.getSymbol(), entity.getCurrentPrice());
                return;
            }
            Double last = getDouble(quote, "last_price");
            if (last == null || last <= 0) {
                log.warn("Quote for {} carried no last_price — keeping the previous price {}", entity.getSymbol(), entity.getCurrentPrice());
                return;
            }
            entity.setCurrentPrice(last);
            entity.setPreviousClose(getDouble(quote, "ohlc.close"));
            entity.setDayHigh(getDouble(quote, "ohlc.high"));
            entity.setDayLow(getDouble(quote, "ohlc.low"));
            entity.setVolume(getLong(quote, "volume"));
            {

                // Calculate day change
                if (entity.getCurrentPrice() != null && entity.getPreviousClose() != null && entity.getPreviousClose() > 0) {
                    double change = entity.getCurrentPrice() - entity.getPreviousClose();
                    double changePct = (change / entity.getPreviousClose()) * 100;
                    entity.setDayChange(change);
                    entity.setDayChangePercent(changePct);
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch quote for {}: {}", entity.getSymbol(), e.getMessage());
        }
    }

    /** {@code {status, data:{symbol:{...}}}} → the per-symbol map; a bare per-symbol map passes through. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> unwrapQuote(Map<String, Object> raw, String symbol) {
        if (raw == null) return null;
        Object data = raw.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object bySymbol = dataMap.get(symbol);
            if (bySymbol == null && dataMap.size() == 1) bySymbol = dataMap.values().iterator().next();
            return bySymbol instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
        return raw.containsKey("last_price") ? raw : null;
    }

    private List<Map<String, Object>> analyzeWithHistoricalData(WatchlistEntity entity) {
        // Fetch daily historical data
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(300);

        List<Map<String, Object>> history = brokerClient.getHistoricalData(
                entity.getSymbol(),
                "day",
                startDate.format(DATE_FORMATTER),
                endDate.format(DATE_FORMATTER));

        if (history == null || history.size() < watchlistConfig.getAnalysis().getMinHistoryDays()) {
            log.warn("Insufficient historical data for {}: {} bars (need {})",
                    entity.getSymbol(),
                    history != null ? history.size() : 0,
                    watchlistConfig.getAnalysis().getMinHistoryDays());
            entity.setAnalysisNotes("Insufficient data for full analysis");
            return history;
        }

        // Convert to TA4J BarSeries
        BarSeries series = StrategyUtils.convertToBarSeries(entity.getSymbol(), history);
        int lastIndex = series.getEndIndex();

        // Calculate indicators
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        EMAIndicator ema20 = new EMAIndicator(closePrice, 20);
        EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
        EMAIndicator ema200 = series.getBarCount() >= 200 ? new EMAIndicator(closePrice, 200) : null;

        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        ATRIndicator atr = new ATRIndicator(series, 14);

        // Get current values
        double currentPrice = entity.getCurrentPrice() != null ?
                entity.getCurrentPrice() : closePrice.getValue(lastIndex).doubleValue();

        double ema20Value = ema20.getValue(lastIndex).doubleValue();
        double ema50Value = ema50.getValue(lastIndex).doubleValue();
        double ema200Value = ema200 != null ? ema200.getValue(lastIndex).doubleValue() : 0;
        double rsiValue = rsi.getValue(lastIndex).doubleValue();
        double atrValue = atr.getValue(lastIndex).doubleValue();

        // Store indicator values
        entity.setEma20(ema20Value);
        entity.setEma50(ema50Value);
        entity.setEma200(ema200Value);
        entity.setRsi14(rsiValue);
        entity.setAtr14(atrValue);

        // EMA position flags
        entity.setPriceAboveEma20(currentPrice > ema20Value);
        entity.setPriceAboveEma50(currentPrice > ema50Value);
        entity.setPriceAboveEma200(ema200Value > 0 && currentPrice > ema200Value);
        entity.setEma20AboveEma50(ema20Value > ema50Value);

        // Determine trend
        String trend = determineTrend(currentPrice, ema20Value, ema50Value, ema200Value);
        entity.setTrendDirection(trend);

        // Calculate scores
        int technicalScore = calculateTechnicalScore(currentPrice, ema20Value, ema50Value, ema200Value, rsiValue);
        int momentumScore = calculateMomentumScore(series, lastIndex, rsiValue, entity);

        entity.setTechnicalScore(technicalScore);
        entity.setMomentumScore(momentumScore);
        // Overall score will be recalculated as blended after fundamentals are fetched
        // Set preliminary score here (will be updated in enrichWithFundamentals)
        entity.setOverallScore((technicalScore * 60 + momentumScore * 40) / 100);

        // Calculate support/resistance
        calculateSupportResistance(entity, series, lastIndex);

        // Generate entry signal
        generateEntrySignal(entity, currentPrice, atrValue, rsiValue);

        // Build analysis notes
        buildAnalysisNotes(entity, currentPrice, ema20Value, ema50Value, ema200Value, rsiValue);
        return history;
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

        // EMA position scoring
        if (price > ema20) score += 10;
        if (price > ema50) score += 10;
        if (ema200 > 0 && price > ema200) score += 10;
        if (ema20 > ema50) score += 10;

        // RSI scoring
        if (rsi >= 40 && rsi <= 60) {
            score += 5; // Neutral zone
        } else if (rsi < 30) {
            score += 15; // Oversold - potential buy
        } else if (rsi > 70) {
            score -= 10; // Overbought - risky entry
        }

        // Price distance from EMAs
        double distanceFrom50 = ((price - ema50) / ema50) * 100;
        if (distanceFrom50 > 0 && distanceFrom50 < 5) {
            score += 5; // Close to EMA50 support
        } else if (distanceFrom50 > 10) {
            score -= 5; // Extended from EMA
        }

        return Math.max(0, Math.min(100, score));
    }

    private int calculateMomentumScore(BarSeries series, int lastIndex, double rsi, WatchlistEntity entity) {
        int score = 50;

        // RSI momentum
        if (rsi > 50 && rsi < 70) {
            score += 15; // Positive momentum, not overbought
        } else if (rsi < 30) {
            score += 20; // Oversold, potential reversal
        } else if (rsi > 70) {
            score -= 10; // Overbought
        }

        // Price vs previous close
        if (entity.getDayChangePercent() != null) {
            double change = entity.getDayChangePercent();
            if (change > 2) score += 10;
            else if (change > 0) score += 5;
            else if (change < -2) score -= 10;
            else if (change < 0) score -= 5;
        }

        // Volume analysis (if available)
        if (series.getBarCount() >= 20) {
            try {
                VolumeIndicator vol = new VolumeIndicator(series);
                SMAIndicator avgVol = new SMAIndicator(vol, 20);

                double currentVol = vol.getValue(lastIndex).doubleValue();
                double avgVolValue = avgVol.getValue(lastIndex).doubleValue();

                if (avgVolValue > 0 && currentVol > avgVolValue * 1.5) {
                    score += 10; // High volume day
                }
            } catch (Exception e) {
                // Volume calculation failed, ignore
            }
        }

        return Math.max(0, Math.min(100, score));
    }

    private void calculateSupportResistance(WatchlistEntity entity, BarSeries series, int lastIndex) {
        // Simple support/resistance: Look for recent swing highs/lows
        int lookback = Math.min(20, lastIndex);

        double currentPrice = entity.getCurrentPrice() != null ? entity.getCurrentPrice() :
                series.getBar(lastIndex).getClosePrice().doubleValue();

        double nearestSupport = 0;
        double nearestResistance = Double.MAX_VALUE;

        for (int i = lastIndex - lookback; i <= lastIndex; i++) {
            if (i < 0) continue;

            double low = series.getBar(i).getLowPrice().doubleValue();
            double high = series.getBar(i).getHighPrice().doubleValue();

            // Find nearest support (below current price)
            if (low < currentPrice && low > nearestSupport) {
                nearestSupport = low;
            }

            // Find nearest resistance (above current price)
            if (high > currentPrice && high < nearestResistance) {
                nearestResistance = high;
            }
        }

        entity.setNearestSupport(nearestSupport > 0 ? nearestSupport : null);
        entity.setNearestResistance(nearestResistance < Double.MAX_VALUE ? nearestResistance : null);
    }

    private void generateEntrySignal(WatchlistEntity entity, double currentPrice, double atr, double rsi) {
        StringBuilder reason = new StringBuilder();
        String signal = "HOLD";
        double confidence = 50;

        int score = entity.getOverallScore() != null ? entity.getOverallScore() : 50;
        String trend = entity.getTrendDirection();
        WatchlistConfig.AnalysisConfig config = watchlistConfig.getAnalysis();

        // Strong Buy conditions
        if (score >= config.getStrongBuyThreshold() && "BULLISH".equals(trend) && rsi < config.getRsiOverbought()) {
            signal = "STRONG_BUY";
            confidence = 80 + (score - config.getStrongBuyThreshold()) * 0.4;
            reason.append("Strong trend alignment with high score. ");

            if (rsi <= config.getRsiOversold()) {
                reason.append("RSI oversold - potential bounce. ");
                confidence += 10;
            }
        }
        // Buy conditions
        else if (score >= config.getBuyThreshold() && !"BEARISH".equals(trend)) {
            signal = "BUY";
            confidence = 60 + (score - config.getBuyThreshold()) * 0.5;
            reason.append("Good technical setup. ");

            if ("BULLISH".equals(trend)) {
                reason.append("Trend is bullish. ");
                confidence += 5;
            }

            if (rsi <= config.getRsiOversold()) {
                reason.append("RSI oversold. ");
                confidence += 10;
            }
        }
        // Avoid conditions
        else if (score < config.getSellThreshold() || "BEARISH".equals(trend)) {
            signal = "AVOID";
            confidence = 30 + (config.getSellThreshold() - score) * 0.5;

            if ("BEARISH".equals(trend)) {
                reason.append("Downtrend - wait for reversal. ");
            }
            if (rsi >= config.getRsiOverbought()) {
                reason.append("RSI overbought - risky entry. ");
            }
            if (score < config.getSellThreshold()) {
                reason.append("Low technical score. ");
            }
        }
        // Hold/Wait
        else {
            signal = "HOLD";
            confidence = 50;
            reason.append("No clear signal. Wait for better setup. ");
        }

        entity.setEntrySignal(signal);
        entity.setSignalReason(reason.toString().trim());
        entity.setSignalConfidence(Math.min(100, Math.max(0, confidence)));

        // Calculate entry levels only for BUY signals
        if ("STRONG_BUY".equals(signal) || "BUY".equals(signal)) {
            // Suggested entry: Current price or slightly below at support
            Double support = entity.getNearestSupport();
            double suggestedEntry = currentPrice;
            if (support != null && support > currentPrice * 0.98) {
                suggestedEntry = support; // Enter at support if close
            }

            double stopLoss = suggestedEntry - (2 * atr);
            double target1 = suggestedEntry + (2 * atr);
            double target2 = suggestedEntry + (4 * atr);
            double riskReward = atr > 0 ? (target1 - suggestedEntry) / (suggestedEntry - stopLoss) : 0;

            entity.setSuggestedEntry(suggestedEntry);
            entity.setSuggestedStopLoss(stopLoss);
            entity.setSuggestedTarget1(target1);
            entity.setSuggestedTarget2(target2);
            entity.setRiskRewardRatio(riskReward);
        }
    }

    private void buildAnalysisNotes(WatchlistEntity entity, double price, double ema20, double ema50, double ema200, double rsi) {
        StringBuilder notes = new StringBuilder();

        notes.append(String.format("Trend: %s | ", entity.getTrendDirection()));
        notes.append(String.format("RSI: %.1f | ", rsi));
        notes.append(String.format("Price: %.2f | ", price));
        notes.append(String.format("EMA20: %.2f | ", ema20));
        notes.append(String.format("EMA50: %.2f", ema50));

        if (ema200 > 0) {
            notes.append(String.format(" | EMA200: %.2f", ema200));
        }

        if (entity.getNearestSupport() != null) {
            notes.append(String.format(" | Support: %.2f", entity.getNearestSupport()));
        }

        if (entity.getNearestResistance() != null) {
            notes.append(String.format(" | Resistance: %.2f", entity.getNearestResistance()));
        }

        entity.setAnalysisNotes(notes.toString());
    }

    /**
     * Enrich entity with fundamental data from StockValuationService and recalculate blended score.
     */
    private void enrichWithFundamentals(WatchlistEntity entity) {
        try {
            StockValuationService.ValuationData valuation = stockValuationService.getValuationData(entity.getSymbol());

            if (valuation != null) {
                entity.setIndustry(valuation.getIndustry());
                entity.setStockPe(valuation.getStockPe());
                entity.setIndustryPe(valuation.getIndustryPe());
                entity.setPeDeviation(valuation.getPeDeviation());
                entity.setMarketCap(valuation.getMarketCap());
                entity.setPriceToBook(valuation.getPriceToBook());
                entity.setEps(valuation.getEps());
                entity.setDividendYield(valuation.getDividendYield());

                // Valuation rating
                entity.setValuationRating(
                    stockValuationService.getValuationInterpretation(valuation.getStockPe(), valuation.getIndustryPe()));

                // Calculate fundamental score
                int fundamentalScore = calculateFundamentalScore(valuation);
                entity.setFundamentalScore(fundamentalScore);

                // Recalculate overall score as blended: 40% technical + 30% fundamental + 30% momentum
                int tech = entity.getTechnicalScore() != null ? entity.getTechnicalScore() : 50;
                int momentum = entity.getMomentumScore() != null ? entity.getMomentumScore() : 50;
                int blendedScore = (tech * 40 + fundamentalScore * 30 + momentum * 30) / 100;
                entity.setOverallScore(blendedScore);

                log.debug("Fundamentals for {}: PE={}, SectorPE={}, Valuation={}, FundScore={}, BlendedScore={}",
                    entity.getSymbol(), valuation.getStockPe(), valuation.getIndustryPe(),
                    entity.getValuationRating(), fundamentalScore, blendedScore);
            }
        } catch (Exception e) {
            log.debug("Failed to fetch fundamentals for {}: {}", entity.getSymbol(), e.getMessage());
            // Keep the preliminary overall score (technical + momentum only)
        }
    }

    /**
     * Calculate fundamental score (0-100) from valuation data.
     */
    private int calculateFundamentalScore(StockValuationService.ValuationData valuation) {
        int score = 50; // Base

        // PE vs Industry PE deviation (-20 to +20)
        if (valuation.getPeDeviation() != null) {
            double dev = valuation.getPeDeviation();
            if (dev < -30) score += 20;
            else if (dev < -15) score += 15;
            else if (dev < -5) score += 10;
            else if (dev <= 5) score += 5;
            else if (dev <= 15) score -= 5;
            else if (dev <= 30) score -= 10;
            else score -= 20;
        }

        // Price-to-Book (+10 to -10)
        if (valuation.getPriceToBook() != null) {
            double pb = valuation.getPriceToBook();
            if (pb < 1.0) score += 10;       // Asset-rich
            else if (pb < 2.0) score += 5;
            else if (pb < 4.0) score += 0;
            else if (pb < 6.0) score -= 5;
            else score -= 10;                 // Very high P/B
        }

        // Market cap bonus (stability)
        if (valuation.getMarketCap() != null) {
            if (valuation.getMarketCap() >= 50000) score += 5;       // Large cap
            else if (valuation.getMarketCap() >= 10000) score += 3;  // Mid cap
        }

        // Dividend yield bonus
        if (valuation.getDividendYield() != null) {
            if (valuation.getDividendYield() > 3) score += 5;
            else if (valuation.getDividendYield() > 1.5) score += 3;
        }

        return Math.max(0, Math.min(100, score));
    }

    private String extractTradingSymbol(String symbol) {
        if (symbol != null && symbol.contains(":")) {
            return symbol.substring(symbol.indexOf(":") + 1);
        }
        return symbol;
    }

    private String extractExchange(String symbol) {
        if (symbol != null && symbol.contains(":")) {
            return symbol.substring(0, symbol.indexOf(":"));
        }
        return "NSE";
    }

    private Double getDouble(Map<String, Object> map, String key) {
        try {
            // Support nested keys like "ohlc.close"
            if (key.contains(".")) {
                String[] parts = key.split("\\.");
                Object current = map;
                for (String part : parts) {
                    if (current instanceof Map) {
                        current = ((Map<?, ?>) current).get(part);
                    } else {
                        return null;
                    }
                }
                if (current instanceof Number) {
                    return ((Number) current).doubleValue();
                }
                return null;
            }

            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private Long getLong(Map<String, Object> map, String key) {
        try {
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Get all analyzed watchlist stocks.
     */
    public List<WatchlistEntity> getAllWatchlistStocks() {
        return watchlistRepository.findActiveOrderByScoreDesc();
    }

    /**
     * Get stocks with buy signals.
     */
    public List<WatchlistEntity> getStocksWithBuySignals() {
        return watchlistRepository.findBuySignals();
    }

    /**
     * Get stocks with strong buy signals.
     */
    public List<WatchlistEntity> getStocksWithStrongBuySignals() {
        return watchlistRepository.findStrongBuySignals();
    }
}
