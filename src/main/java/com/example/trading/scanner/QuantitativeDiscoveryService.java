package com.example.trading.scanner;

import com.example.trading.ai.NseDataService;
import com.example.trading.holdings.StockValuationService;
import com.example.trading.holdings.StockValuationService.ValuationData;
import com.example.trading.intelligence.recommendation.RecommendationTracker;
import com.example.trading.marketdata.MarketDataService;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.RecommendationEntity;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuantitativeDiscoveryService {

    private final NseDataService nseDataService;
    private final StockValuationService valuationService;
    private final MarketDataService marketDataService;
    private final MultibaggerScreenerService multibaggerScreenerService;
    private final HoldingsRepository holdingsRepository;
    private final RecommendationTracker recommendationTracker;

    /** Discovery score at or above which a pick is recorded for accuracy tracking (SPEC.md §23). */
    private static final int RECOMMENDATION_THRESHOLD = 60;

    /**
     * Run quantitative discovery scan on the full universe.
     * Fetches real data, applies filters, and ranks opportunities.
     */
    public DiscoveryReport runDiscoveryScan() {
        log.info("Quantitative Discovery: Starting data-driven scan...");
        long startTime = System.currentTimeMillis();

        // Build candidate list: screening universe + holdings not in universe
        Set<String> candidates = new LinkedHashSet<>(multibaggerScreenerService.getScreeningUniverse());
        try {
            List<HoldingsEntity> holdings = holdingsRepository.findAll();
            for (HoldingsEntity h : holdings) {
                if (h.getSymbol() != null) candidates.add(h.getSymbol());
            }
        } catch (Exception e) {
            log.debug("Holdings unavailable for discovery: {}", e.getMessage());
        }

        log.info("Quantitative Discovery: Scanning {} candidates...", candidates.size());

        List<DiscoveredOpportunity> opportunities = new ArrayList<>();
        int scanned = 0, passed = 0;

        for (String symbol : candidates) {
            try {
                DiscoveredOpportunity opp = analyzeStock(symbol);
                scanned++;
                if (opp != null && opp.getDiscoveryScore() >= 40) {
                    opportunities.add(opp);
                    passed++;
                }
            } catch (Exception e) {
                log.debug("Discovery scan failed for {}: {}", symbol, e.getMessage());
            }
        }

        // Sort by discovery score descending
        opportunities.sort(Comparator.comparingInt(DiscoveredOpportunity::getDiscoveryScore).reversed());

        // SPEC.md §23: record picks crossing the recommendation threshold
        for (DiscoveredOpportunity opp : opportunities) {
            if (opp.getDiscoveryScore() < RECOMMENDATION_THRESHOLD) continue;
            EntryExitLevels lvl = opp.getLevels();
            // SPEC.md §23.2: persist the 5 sub-scores so per-dimension IC can be
            // computed for this engine (it has no standalone score table). Order
            // preserved for stable rendering. java.util.LinkedHashMap import below.
            java.util.Map<String, Integer> dims = new java.util.LinkedHashMap<>();
            dims.put("Earnings Growth", opp.getEarningsScore());
            dims.put("Insider Activity", opp.getInsiderScore());
            dims.put("Valuation", opp.getValuationScore());
            dims.put("Price Momentum", opp.getMomentumScore());
            dims.put("Volume", opp.getVolumeScore());
            recommendationTracker.record(
                    RecommendationEntity.Source.QUANT_DISCOVERY,
                    opp.getSymbol(),
                    opp.getCurrentPrice(),
                    opp.getDiscoveryScore(),
                    null,
                    opp.getGrowthVerdict(),
                    lvl != null ? lvl.getTarget1() : null,
                    lvl != null ? lvl.getStopLoss() : null,
                    opp.getIndustry(),
                    null,
                    dims);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Quantitative Discovery: Scanned {}, passed filters: {}, elapsed: {}ms",
                scanned, passed, elapsed);

        DiscoveryReport report = new DiscoveryReport();
        report.setScanDate(LocalDate.now());
        report.setTotalScanned(scanned);
        report.setTotalPassed(passed);
        report.setOpportunities(opportunities.stream().limit(30).collect(Collectors.toList()));
        report.setElapsedMs(elapsed);
        return report;
    }

    /**
     * Analyze a single stock for discovery potential.
     * Returns null if insufficient data.
     */
    /**
     * Analyze a single stock for discovery potential with entry/exit levels.
     */
    public DiscoveredOpportunity analyzeSingleStock(String symbol) {
        return analyzeStock(symbol);
    }

    private DiscoveredOpportunity analyzeStock(String symbol) {
        String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;

        // 1. Fetch earnings growth data
        NseDataService.EarningsGrowthData earnings = null;
        try {
            earnings = nseDataService.analyzeEarningsGrowth(tradingSymbol);
        } catch (Exception e) {
            log.debug("Earnings unavailable for {}", symbol);
        }

        // 2. Fetch shareholding history
        NseDataService.ShareholdingHistory shareholding = null;
        try {
            shareholding = nseDataService.fetchShareholdingHistory(tradingSymbol);
        } catch (Exception e) {
            log.debug("Shareholding unavailable for {}", symbol);
        }

        // 3. Fetch valuation data
        ValuationData valuation = null;
        try {
            valuation = valuationService.getValuationData(symbol);
        } catch (Exception e) {
            log.debug("Valuation unavailable for {}", symbol);
        }

        // 4. Fetch price history (6 months daily candles)
        List<Map<String, Object>> priceHistory = null;
        try {
            String from = LocalDate.now().minusMonths(6).format(DateTimeFormatter.ISO_DATE);
            String to = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
            priceHistory = marketDataService.getRecentCandles(symbol, "day", from, to);
        } catch (Exception e) {
            log.debug("Price history unavailable for {}", symbol);
        }

        // Need at least price data to proceed
        if (priceHistory == null || priceHistory.size() < 20) return null;

        double currentPrice = toDouble(priceHistory.get(priceHistory.size() - 1).get("close"));
        if (currentPrice <= 0) return null;

        // ========== SCORING ==========
        int score = 0;
        List<String> triggers = new ArrayList<>(); // reasons this stock qualifies
        List<String> risks = new ArrayList<>();

        // --- Earnings Growth Score (0-25) ---
        int earningsScore = 0;
        if (earnings != null && earnings.getGrowthVerdict() != null) {
            switch (earnings.getGrowthVerdict()) {
                case "STRONG_GROWTH":
                    earningsScore = 25;
                    triggers.add(String.format("Strong earnings: Rev %+.1f%% YoY, Profit %+.1f%% YoY",
                        safe(earnings.getYoyRevenueGrowth()), safe(earnings.getYoyProfitGrowth())));
                    break;
                case "MODERATE_GROWTH":
                    earningsScore = 15;
                    triggers.add(String.format("Moderate growth: Rev %+.1f%% YoY", safe(earnings.getYoyRevenueGrowth())));
                    break;
                case "STAGNANT":
                    earningsScore = 5;
                    break;
                case "DECLINING":
                    earningsScore = 0;
                    risks.add(String.format("Declining earnings: Rev %+.1f%% YoY", safe(earnings.getYoyRevenueGrowth())));
                    break;
            }
            if (earnings.isEarningsAccelerating()) {
                earningsScore = Math.min(25, earningsScore + 5);
                triggers.add("Earnings accelerating (growth rate increasing)");
            }
            if (earnings.getLatestNetMargin() != null && earnings.getLatestNetMargin() > 15) {
                earningsScore = Math.min(25, earningsScore + 3);
                triggers.add(String.format("High net margin: %.1f%%", earnings.getLatestNetMargin()));
            }
            if (earnings.getRevenueCAGR() != null && earnings.getRevenueCAGR() > 20) {
                earningsScore = Math.min(25, earningsScore + 3);
                triggers.add(String.format("Revenue CAGR: %.1f%%", earnings.getRevenueCAGR()));
            }
        }
        score += earningsScore;

        // --- Insider Activity Score (0-20) ---
        int insiderScore = 0;
        if (shareholding != null && shareholding.getInsiderSignal() != null) {
            switch (shareholding.getInsiderSignal()) {
                case "STRONG_BUY":
                    insiderScore = 20;
                    triggers.add(String.format("Promoter increasing stake: %+.2f%%", safe(shareholding.getPromoterChange())));
                    break;
                case "BUY":
                    insiderScore = 12;
                    triggers.add("Promoter marginally increasing stake");
                    break;
                case "NEUTRAL":
                    insiderScore = 8;
                    break;
                case "SELL":
                    insiderScore = 3;
                    risks.add("Promoter reducing stake");
                    break;
                case "STRONG_SELL":
                    insiderScore = 0;
                    risks.add(String.format("Promoter significantly reducing: %+.2f%%", safe(shareholding.getPromoterChange())));
                    break;
            }
            if (shareholding.isFiiIncreasing()) {
                insiderScore = Math.min(20, insiderScore + 5);
                triggers.add("FII increasing holdings");
            }
            if (shareholding.getPledgePercent() != null && shareholding.getPledgePercent() > 20) {
                insiderScore = Math.max(0, insiderScore - 5);
                risks.add(String.format("High promoter pledge: %.1f%%", shareholding.getPledgePercent()));
            }
        }
        score += insiderScore;

        // --- Valuation Score (0-20) ---
        int valuationScore = 0;
        if (valuation != null) {
            Double stockPE = valuation.getStockPe();
            Double sectorPE = valuation.getIndustryPe();
            if (stockPE != null && sectorPE != null && sectorPE > 0) {
                double peRatio = stockPE / sectorPE;
                if (peRatio < 0.7) {
                    valuationScore = 20;
                    triggers.add(String.format("Deeply undervalued: PE %.1f vs sector %.1f (%.0f%% discount)",
                            stockPE, sectorPE, (1 - peRatio) * 100));
                } else if (peRatio < 0.9) {
                    valuationScore = 15;
                    triggers.add(String.format("Undervalued: PE %.1f vs sector %.1f", stockPE, sectorPE));
                } else if (peRatio < 1.1) {
                    valuationScore = 10;
                } else if (peRatio < 1.3) {
                    valuationScore = 5;
                } else {
                    valuationScore = 0;
                    risks.add(String.format("Premium valuation: PE %.1f vs sector %.1f", stockPE, sectorPE));
                }
            }
            if (valuation.getDividendYield() != null && valuation.getDividendYield() > 2.0) {
                valuationScore = Math.min(20, valuationScore + 3);
                triggers.add(String.format("Dividend yield: %.1f%%", valuation.getDividendYield()));
            }
        }
        score += valuationScore;

        // --- Price Momentum Score (0-20) ---
        int momentumScore = 0;
        int size = priceHistory.size();
        // 1-month return
        if (size >= 22) {
            double price1m = toDouble(priceHistory.get(size - 22).get("close"));
            double ret1m = (currentPrice - price1m) / price1m * 100;
            if (ret1m > 10) {
                momentumScore += 8;
                triggers.add(String.format("Strong 1M momentum: %+.1f%%", ret1m));
            } else if (ret1m > 3) {
                momentumScore += 5;
            } else if (ret1m < -10) {
                risks.add(String.format("Weak 1M: %+.1f%%", ret1m));
            }
        }
        // 3-month return
        if (size >= 63) {
            double price3m = toDouble(priceHistory.get(size - 63).get("close"));
            double ret3m = (currentPrice - price3m) / price3m * 100;
            if (ret3m > 15) {
                momentumScore += 7;
            } else if (ret3m > 5) {
                momentumScore += 4;
            }
        }
        // Higher lows pattern (bullish structure)
        if (hasHigherLows(priceHistory)) {
            momentumScore += 5;
            triggers.add("Higher lows pattern (bullish structure)");
        }
        momentumScore = Math.min(20, momentumScore);
        score += momentumScore;

        // --- Volume Score (0-15) ---
        int volumeScore = 0;
        if (size >= 60) {
            double avg60Vol = 0;
            for (int i = size - 60; i < size; i++) {
                avg60Vol += toDouble(priceHistory.get(i).get("volume"));
            }
            avg60Vol /= 60;

            double avg10Vol = 0;
            for (int i = size - 10; i < size; i++) {
                avg10Vol += toDouble(priceHistory.get(i).get("volume"));
            }
            avg10Vol /= 10;

            if (avg60Vol > 0) {
                double volRatio = avg10Vol / avg60Vol;
                if (volRatio >= 2.0) {
                    volumeScore = 15;
                    triggers.add(String.format("Volume surge: %.1fx average", volRatio));
                } else if (volRatio >= 1.5) {
                    volumeScore = 10;
                    triggers.add(String.format("Rising volume: %.1fx average", volRatio));
                } else if (volRatio >= 1.0) {
                    volumeScore = 5;
                }
            }
        }
        score += volumeScore;

        // --- Financial Quality Gate (SPEC §6) ---
        // Standalone signal; used to cap the composite for fragile balance sheets.
        NseDataService.FinancialQualityData fq = null;
        try {
            fq = nseDataService.analyzeFinancialQuality(tradingSymbol);
            if (fq != null && fq.getQualityScore() != null) {
                if (fq.getStrengths() != null) triggers.addAll(fq.getStrengths());
                if (fq.getRedFlags() != null) risks.addAll(fq.getRedFlags());
                if ("HIGH_RISK".equals(fq.getQualityVerdict())) {
                    score = Math.min(score, 49);
                    risks.add("Financial quality HIGH_RISK — discovery score capped");
                }
            }
        } catch (Exception e) {
            log.debug("Financial quality unavailable for {}", symbol);
        }

        // ========== BUILD ENTRY/EXIT LEVELS ==========
        EntryExitLevels levels = calculateEntryExitLevels(priceHistory, currentPrice);

        // ========== BUILD RESULT ==========
        DiscoveredOpportunity opp = new DiscoveredOpportunity();
        opp.setSymbol(symbol);
        opp.setCurrentPrice(currentPrice);
        opp.setDiscoveryScore(score);
        opp.setEarningsScore(earningsScore);
        opp.setInsiderScore(insiderScore);
        opp.setValuationScore(valuationScore);
        opp.setMomentumScore(momentumScore);
        opp.setVolumeScore(volumeScore);
        if (fq != null) {
            opp.setFinancialQualityScore(fq.getQualityScore());
            opp.setFinancialQualityVerdict(fq.getQualityVerdict());
        }
        opp.setTriggers(triggers);
        opp.setRisks(risks);
        opp.setLevels(levels);

        // Add metadata
        if (valuation != null) {
            opp.setIndustry(valuation.getIndustry());
            opp.setStockPE(valuation.getStockPe());
            opp.setSectorPE(valuation.getIndustryPe());
            opp.setMarketCapCr(valuation.getMarketCap());
        }
        if (earnings != null) {
            opp.setGrowthVerdict(earnings.getGrowthVerdict());
            opp.setYoyRevenueGrowth(earnings.getYoyRevenueGrowth());
            opp.setYoyProfitGrowth(earnings.getYoyProfitGrowth());
            opp.setNetMargin(earnings.getLatestNetMargin());
        }
        if (shareholding != null) {
            opp.setInsiderSignal(shareholding.getInsiderSignal());
            opp.setPromoterChange(shareholding.getPromoterChange());
        }

        // Check if in holdings
        try {
            opp.setInHoldings(holdingsRepository.findBySymbol(symbol).isPresent());
        } catch (Exception e) {
            // ignore
        }

        return opp;
    }

    /**
     * Calculate entry/exit levels from price history.
     */
    private EntryExitLevels calculateEntryExitLevels(List<Map<String, Object>> history, double currentPrice) {
        EntryExitLevels levels = new EntryExitLevels();
        int size = history.size();

        // ---- Support & Resistance from price action ----
        // Find recent swing lows (support) and swing highs (resistance)
        List<Double> swingHighs = new ArrayList<>();
        List<Double> swingLows = new ArrayList<>();
        int lookback = Math.min(size, 120); // ~6 months

        for (int i = size - lookback + 2; i < size - 2; i++) {
            double high = toDouble(history.get(i).get("high"));
            double prevHigh = toDouble(history.get(i - 1).get("high"));
            double nextHigh = toDouble(history.get(i + 1).get("high"));
            double prevPrevHigh = toDouble(history.get(i - 2).get("high"));
            double nextNextHigh = toDouble(history.get(i + 2).get("high"));

            // Swing high: higher than 2 candles on each side
            if (high > prevHigh && high > nextHigh && high > prevPrevHigh && high > nextNextHigh) {
                swingHighs.add(high);
            }

            double low = toDouble(history.get(i).get("low"));
            double prevLow = toDouble(history.get(i - 1).get("low"));
            double nextLow = toDouble(history.get(i + 1).get("low"));
            double prevPrevLow = toDouble(history.get(i - 2).get("low"));
            double nextNextLow = toDouble(history.get(i + 2).get("low"));

            // Swing low: lower than 2 candles on each side
            if (low < prevLow && low < nextLow && low < prevPrevLow && low < nextNextLow) {
                swingLows.add(low);
            }
        }

        // Find nearest support below current price
        List<Double> supportsBelow = swingLows.stream()
                .filter(s -> s < currentPrice)
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        if (supportsBelow.size() >= 1) levels.setSupport1(supportsBelow.get(0));
        if (supportsBelow.size() >= 2) levels.setSupport2(supportsBelow.get(1));

        // Find nearest resistance above current price
        List<Double> resistancesAbove = swingHighs.stream()
                .filter(r -> r > currentPrice)
                .sorted()
                .collect(Collectors.toList());
        if (resistancesAbove.size() >= 1) levels.setResistance1(resistancesAbove.get(0));
        if (resistancesAbove.size() >= 2) levels.setResistance2(resistancesAbove.get(1));

        // ---- EMA Levels (dynamic support/resistance) ----
        if (size >= 200) {
            levels.setEma20(calculateEMA(history, 20));
            levels.setEma50(calculateEMA(history, 50));
            levels.setEma200(calculateEMA(history, 200));
        } else if (size >= 50) {
            levels.setEma20(calculateEMA(history, 20));
            levels.setEma50(calculateEMA(history, 50));
        } else if (size >= 20) {
            levels.setEma20(calculateEMA(history, 20));
        }

        // ---- RSI ----
        levels.setRsi14(calculateRSI(history, 14));

        // ---- ATR (Average True Range for stop loss and targets) ----
        double atr = calculateATR(history, 14);
        levels.setAtr14(atr);

        // ---- 52-week high/low ----
        int lookback52w = Math.min(size, 252);
        double high52w = Double.MIN_VALUE, low52w = Double.MAX_VALUE;
        for (int i = size - lookback52w; i < size; i++) {
            double h = toDouble(history.get(i).get("high"));
            double l = toDouble(history.get(i).get("low"));
            if (h > high52w) high52w = h;
            if (l < low52w) low52w = l;
        }
        levels.setHigh52Week(high52w);
        levels.setLow52Week(low52w);

        // ---- Entry Zone ----
        // Best entry is near support or EMA pullback
        double idealEntry = currentPrice; // default
        if (levels.getSupport1() != null) {
            // Entry near support with small buffer above it
            idealEntry = levels.getSupport1() + (atr * 0.2);
        }
        levels.setIdealEntryZoneLow(idealEntry);
        levels.setIdealEntryZoneHigh(idealEntry + (atr * 0.5));

        // ---- Stop Loss ----
        // Below support or 1.5x ATR below entry
        double stopLoss;
        if (levels.getSupport1() != null) {
            stopLoss = levels.getSupport1() - (atr * 0.5); // below support with buffer
        } else {
            stopLoss = currentPrice - (atr * 2.0);
        }
        levels.setStopLoss(stopLoss);

        // ---- Targets (ATR-based R:R) ----
        double risk = currentPrice - stopLoss;
        if (risk > 0) {
            levels.setTarget1(currentPrice + (risk * 1.5)); // 1.5R
            levels.setTarget2(currentPrice + (risk * 2.5)); // 2.5R
            levels.setTarget3(currentPrice + (risk * 4.0)); // 4R
            levels.setRiskRewardRatio(risk > 0 ? (levels.getTarget2() - currentPrice) / risk : 0);
        }

        // ---- Current Signal Assessment ----
        String entrySignal;
        if (levels.getRsi14() != null && levels.getRsi14() < 35) {
            entrySignal = "OVERSOLD — Good entry zone";
        } else if (levels.getRsi14() != null && levels.getRsi14() > 75) {
            entrySignal = "OVERBOUGHT — Wait for pullback";
        } else if (levels.getEma50() != null && currentPrice > levels.getEma50() &&
                   currentPrice < levels.getEma50() * 1.02) {
            entrySignal = "EMA50 PULLBACK — Good entry near support";
        } else if (levels.getSupport1() != null &&
                   currentPrice < levels.getSupport1() * 1.02) {
            entrySignal = "NEAR SUPPORT — Good entry zone";
        } else if (levels.getResistance1() != null &&
                   currentPrice > levels.getResistance1() * 0.98) {
            entrySignal = "NEAR RESISTANCE — Wait for breakout confirmation";
        } else if (levels.getEma20() != null && levels.getEma50() != null &&
                   levels.getEma20() > levels.getEma50() && currentPrice > levels.getEma20()) {
            entrySignal = "UPTREND — Enter on dips to EMA20";
        } else if (levels.getEma20() != null && levels.getEma50() != null &&
                   levels.getEma20() < levels.getEma50()) {
            entrySignal = "DOWNTREND — Avoid or wait for reversal";
        } else {
            entrySignal = "NEUTRAL — No clear entry signal";
        }
        levels.setEntrySignal(entrySignal);

        // ---- Exit Signal Assessment ----
        String exitSignal;
        if (levels.getRsi14() != null && levels.getRsi14() > 80) {
            exitSignal = "RSI OVERBOUGHT — Consider booking profits";
        } else if (levels.getResistance1() != null && currentPrice > levels.getResistance1() * 0.98) {
            exitSignal = String.format("NEAR RESISTANCE %.2f — Book partial profits", levels.getResistance1());
        } else if (levels.getEma20() != null && currentPrice < levels.getEma20()) {
            exitSignal = "BELOW EMA20 — Tighten stop loss";
        } else if (levels.getEma50() != null && currentPrice < levels.getEma50()) {
            exitSignal = "BELOW EMA50 — Exit or reduce position";
        } else {
            exitSignal = "HOLD — No exit signal";
        }
        levels.setExitSignal(exitSignal);

        // ---- Price position summary ----
        double distFromHigh = (high52w - currentPrice) / high52w * 100;
        double distFromLow = (currentPrice - low52w) / low52w * 100;
        levels.setPctFrom52WeekHigh(distFromHigh);
        levels.setPctFrom52WeekLow(distFromLow);

        return levels;
    }

    // ========== Technical Calculations ==========

    private double calculateEMA(List<Map<String, Object>> history, int period) {
        if (history.size() < period) return 0;
        double multiplier = 2.0 / (period + 1);
        // Start with SMA
        double ema = 0;
        for (int i = 0; i < period; i++) {
            ema += toDouble(history.get(i).get("close"));
        }
        ema /= period;
        // Calculate EMA
        for (int i = period; i < history.size(); i++) {
            double close = toDouble(history.get(i).get("close"));
            ema = (close - ema) * multiplier + ema;
        }
        return ema;
    }

    private Double calculateRSI(List<Map<String, Object>> history, int period) {
        if (history.size() < period + 1) return null;
        double gainSum = 0, lossSum = 0;
        for (int i = history.size() - period; i < history.size(); i++) {
            double change = toDouble(history.get(i).get("close")) - toDouble(history.get(i - 1).get("close"));
            if (change > 0) gainSum += change;
            else lossSum += Math.abs(change);
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double calculateATR(List<Map<String, Object>> history, int period) {
        if (history.size() < period + 1) return 0;
        double atrSum = 0;
        for (int i = history.size() - period; i < history.size(); i++) {
            double high = toDouble(history.get(i).get("high"));
            double low = toDouble(history.get(i).get("low"));
            double prevClose = toDouble(history.get(i - 1).get("close"));
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            atrSum += tr;
        }
        return atrSum / period;
    }

    private boolean hasHigherLows(List<Map<String, Object>> history) {
        int size = history.size();
        if (size < 40) return false;
        int step = size / 4;
        double[] lows = new double[4];
        for (int i = 0; i < 4; i++) {
            int start = i * step;
            int end = Math.min(start + step, size);
            lows[i] = Double.MAX_VALUE;
            for (int j = start; j < end; j++) {
                double low = toDouble(history.get(j).get("low"));
                if (low < lows[i]) lows[i] = low;
            }
        }
        return lows[1] > lows[0] && lows[2] > lows[1] && lows[3] > lows[2];
    }

    private double toDouble(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (Exception e) { return 0; }
    }

    private double safe(Double val) {
        return val != null ? val : 0.0;
    }

    // ========== DTOs ==========

    @Data
    public static class DiscoveryReport {
        private LocalDate scanDate;
        private int totalScanned;
        private int totalPassed;
        private List<DiscoveredOpportunity> opportunities;
        private long elapsedMs;
    }

    @Data
    public static class DiscoveredOpportunity {
        private String symbol;
        private double currentPrice;
        private int discoveryScore;        // 0-100 composite
        private int earningsScore;         // 0-25
        private int insiderScore;          // 0-20
        private int valuationScore;        // 0-20
        private int momentumScore;         // 0-20
        private int volumeScore;           // 0-15
        private Integer financialQualityScore;    // 0-100 standalone quality signal (SPEC §6)
        private String financialQualityVerdict;   // HIGH_QUALITY / DECENT / AVERAGE / WEAK / HIGH_RISK
        private List<String> triggers;     // why this stock qualifies
        private List<String> risks;        // risk flags
        private EntryExitLevels levels;    // entry/exit data

        // Metadata
        private String industry;
        private Double stockPE;
        private Double sectorPE;
        private Double marketCapCr;
        private String growthVerdict;
        private Double yoyRevenueGrowth;
        private Double yoyProfitGrowth;
        private Double netMargin;
        private String insiderSignal;
        private Double promoterChange;
        private boolean inHoldings;
    }

    @Data
    public static class EntryExitLevels {
        // Support & Resistance
        private Double support1;
        private Double support2;
        private Double resistance1;
        private Double resistance2;

        // Moving Averages
        private Double ema20;
        private Double ema50;
        private Double ema200;

        // Indicators
        private Double rsi14;
        private Double atr14;

        // 52-week range
        private Double high52Week;
        private Double low52Week;
        private Double pctFrom52WeekHigh;
        private Double pctFrom52WeekLow;

        // Entry levels
        private Double idealEntryZoneLow;
        private Double idealEntryZoneHigh;
        private String entrySignal;        // OVERSOLD, EMA_PULLBACK, NEAR_SUPPORT, etc.

        // Exit levels
        private Double stopLoss;
        private Double target1;            // 1.5R
        private Double target2;            // 2.5R
        private Double target3;            // 4R
        private Double riskRewardRatio;
        private String exitSignal;         // OVERBOUGHT, NEAR_RESISTANCE, HOLD, etc.
    }
}
