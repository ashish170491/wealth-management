package com.example.trading.ai;

import com.example.trading.fiidii.FiiDiiDTO.SectorFlow;
import com.example.trading.fiidii.FiiDiiSectorAnalysisService;
import com.example.trading.holdings.StockValuationService;
import com.example.trading.holdings.StockValuationService.ValuationData;
import com.example.trading.marketdata.MarketDataService;
import com.example.trading.multibagger.MultibaggerScore;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.persistence.*;
import com.example.trading.watchlist.WatchlistEntity;
import com.example.trading.watchlist.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deep Stock Research Agent.
 * Gathers ALL available data for a stock from every service in the system,
 * builds a comprehensive research prompt, and gets AI-powered investment analysis.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockResearchService {

    private final AiService aiService;
    private final MarketDataService marketDataService;
    private final MultibaggerScreenerService multibaggerScreenerService;
    private final StockValuationService valuationService;
    private final HoldingsRepository holdingsRepository;
    private final WatchlistRepository watchlistRepository;
    private final MultibaggerScoreRepository multibaggerScoreRepository;
    private final FiiDiiSectorAnalysisService fiiDiiSectorAnalysisService;
    private final StockNewsService stockNewsService;
    private final NseDataService nseDataService;
    private final PeerComparisonService peerComparisonService;
    private final IntrinsicValuationService intrinsicValuationService;
    private final AnalystSignalService analystSignalService;
    private final com.example.trading.concall.ConcallAnalysisService concallAnalysisService;
    private final com.example.trading.fundamentals.CapexCycleService capexCycleService;

    private static final String RESEARCH_SYSTEM_PROMPT =
        "You are a senior equity research analyst at a top Indian brokerage. " +
        "You are preparing a detailed investment research note for a client. " +
        "You have been given comprehensive data: technicals, fundamentals, quarterly earnings, shareholding patterns, " +
        "institutional flows, recent news, peer comparison, and price structure. " +
        "Analyze ALL the data. Be specific with numbers. Cross-reference data points — " +
        "e.g., if promoter holding is rising AND earnings are growing, that's a strong signal. " +
        "Don't be generic. Give a clear, decisive verdict.\n\n" +
        "Structure your analysis as:\n" +
        "1. VERDICT: One line — Strong Buy / Buy / Hold / Reduce / Sell with conviction level (High/Medium/Low)\n" +
        "2. INVESTMENT THESIS: 2-3 sentences on the core story — what makes this stock special or concerning\n" +
        "3. EARNINGS & GROWTH: Revenue/profit trends from quarterly data, EPS trajectory, growth rate\n" +
        "4. TECHNICAL PICTURE: Key levels, trend, momentum, volume analysis, chart patterns\n" +
        "_WEALTH SIGNALS_: In FUNDAMENTAL VIEW and MULTIBAGGER POTENTIAL, weigh the WEALTH SIGNALS block — expanding gross margin (pricing power), consistent earnings (steady compounder), and high delivery % (genuine accumulation) are hallmarks of long-term wealth creators; flag contracting margins or erratic earnings as quality concerns.\n" +
        "_CAPITAL EFFICIENCY_: Treat the CAPITAL EFFICIENCY block (ROCE, ROE, Debt-to-Equity, cash conversion from the annual balance sheet) as the single strongest fundamental input — sustained high ROCE/ROE with low debt and strong cash conversion is the textbook multibagger profile; high debt or weak cash conversion is a serious concern. Lead the FUNDAMENTAL VIEW with these.\n" +
        "5. FUNDAMENTAL VIEW: Valuation (PE vs sector, PB), earnings quality, profitability, BALANCE-SHEET QUALITY, AND REVERSE-DCF EXPECTATION GAP — " +
        "explicitly reference interest coverage, cash-flow-to-profit ratio, promoter pledge, Financial Quality verdict, and the DCF Expectation Gap (how much growth the market is pricing in vs what the business has actually delivered). " +
        "A stock can look 'cheap on PE' while being financially fragile OR while implying unrealistic growth — call both out if you see them. " +
        "Respect the DCF caveat: long-duration compounders (IT / platforms / pharma) may be unfairly flagged EXPENSIVE; explain which bucket the stock falls into.\n" +
        "6. OWNERSHIP ANALYSIS: Promoter holding trend, institutional interest, insider signals\n" +
        "7. PEER COMPARISON: How does this stock stack up vs sector peers? Better/worse on what metrics?\n" +
        "8. NEWS & CATALYSTS: Recent news impact, upcoming catalysts, corporate actions. " +
        "CROSS-REFERENCE the ANALYST SIGNAL block: the trend-break verdict captures whether the latest quarter beat its own 3-quarter trajectory, and brokerage-action counts show net upgrades/downgrades. " +
        "Note explicitly: this is NOT paid-consensus data — treat it as supporting evidence only.\n" +
        "9. INSTITUTIONAL ACTIVITY: FII/DII sector flows, smart money signals\n" +
        "10. RISK FACTORS: 3-4 specific risks (not generic ones) — quantify where possible\n" +
        "11. ACTION PLAN: Specific entry zone, stop loss, 3 targets (short/medium/long term) with reasoning\n" +
        "12. MULTIBAGGER POTENTIAL: Score 1-10. What catalysts could make this a 3-5x? What's the timeline?\n\n" +
        "Use plain text. Be decisive, not wishy-washy. If data is insufficient for a section, say so.";

    /**
     * Run deep AI-powered research on a single stock.
     * Gathers data from ALL available sources and generates comprehensive analysis.
     *
     * @param symbol Stock symbol (e.g., NSE:RELIANCE)
     * @return Research report as structured text, or empty string if AI unavailable
     */
    public String researchStock(String symbol) {
        if (!aiService.isAvailable()) {
            log.warn("Stock Research: AI service not available");
            return "";
        }

        log.info("Stock Research: Starting deep analysis for {}", symbol);
        long startTime = System.currentTimeMillis();

        StringBuilder data = new StringBuilder();
        data.append("=== DEEP STOCK RESEARCH DATA FOR ").append(symbol).append(" ===\n");
        data.append("Research Date: ").append(LocalDate.now().format(DateTimeFormatter.ISO_DATE)).append("\n\n");

        // 1. Current Price & Recent Price Action
        appendPriceData(data, symbol);

        // 2. Holdings Data (if owned)
        appendHoldingsData(data, symbol);

        // 3. Multibagger Screening (7-dimension score)
        appendMultibaggerData(data, symbol);

        // 4. Valuation Data (PE, PB, EPS, Market Cap)
        appendValuationData(data, symbol);

        // 5. Watchlist Data (if in watchlist)
        appendWatchlistData(data, symbol);


        // 7. Sector Reversal Signals

        // 8. Multibagger Score History (trend)
        appendScoreTrend(data, symbol);

        // 9. Sector FII/DII Flows
        appendSectorFlows(data, symbol);

        // 10. Historical Price Data (key levels from candles)
        appendHistoricalAnalysis(data, symbol);

        // 11. Recent News (Google News RSS)
        appendNewsData(data, symbol);

        // 12. Quarterly Financial Results (NSE)
        appendQuarterlyResults(data, symbol);

        // 13. Shareholding Pattern (NSE)
        appendShareholdingData(data, symbol);

        // 14. Corporate Announcements (NSE)
        appendCorporateAnnouncements(data, symbol);

        // 15. Peer Comparison (sector peers from our universe)
        appendPeerComparison(data, symbol);

        // 16. Financial Quality — balance-sheet / cash-flow depth (SPEC §6)
        appendFinancialQualityData(data, symbol);

        // 17. Intrinsic Valuation — reverse DCF (SPEC §12.5)
        appendIntrinsicValuationData(data, symbol);

        // 18. Analyst Signal — earnings trend-break + brokerage action (SPEC §24)
        appendAnalystSignalData(data, symbol);

        // 19. Wealth Signals — gross-margin trend, earnings consistency, delivery % (SPEC §12.7)
        appendWealthSignalsData(data, symbol);

        // 20. Capital Efficiency — ROCE/ROE/D-E/cash-conversion from annual balance sheet (SPEC §12.8)
        appendCapitalEfficiencyData(data, symbol);

        // 21. Capex Cycle — CWIP / net block / capex-to-depreciation (SPEC §31)
        appendCapexCycleData(data, symbol);

        // 22. Management delivery record — measured guidance-met ratio (SPEC §34)
        appendManagementCredibility(data, symbol);

        data.append("\n=== END OF DATA (22 DIMENSIONS) ===\n");
        data.append("\nProvide your complete research analysis based on ALL the above data. Cross-reference multiple data points.");

        log.info("Stock Research: Data gathered for {} in {}ms, sending to AI...",
                symbol, System.currentTimeMillis() - startTime);

        String analysis = aiService.analyze(RESEARCH_SYSTEM_PROMPT, data.toString());

        log.info("Stock Research: Analysis complete for {} ({} chars)", symbol,
                analysis != null ? analysis.length() : 0);

        return analysis != null ? analysis : "";
    }

    /**
     * Research a stock and return as formatted HTML for email reports.
     */
    public String researchStockAsHtml(String symbol) {
        String analysis = researchStock(symbol);
        if (analysis.isEmpty()) return "";
        return AiService.formatAiResponseAsHtml("Deep Research: " + symbol, analysis);
    }

    // ============================================================
    // Data Gathering Methods
    // ============================================================

    private void appendPriceData(StringBuilder data, String symbol) {
        data.append("--- CURRENT PRICE ---\n");
        try {
            Double price = marketDataService.getCurrentPrice(symbol);
            if (price != null && price > 0) {
                data.append("Current Price: ").append(String.format("%.2f", price)).append("\n");
            } else {
                data.append("Current price not available\n");
            }
        } catch (Exception e) {
            data.append("Price data unavailable: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private void appendHoldingsData(StringBuilder data, String symbol) {
        try {
            Optional<HoldingsEntity> holding = holdingsRepository.findBySymbol(symbol);
            if (holding.isPresent()) {
                HoldingsEntity h = holding.get();
                data.append("--- HOLDINGS DATA (YOU OWN THIS STOCK) ---\n");
                data.append(String.format("Quantity: %d, Avg Price: %.2f, Current: %.2f\n",
                        h.getQuantity(), h.getAveragePrice(), h.getCurrentPrice()));
                data.append(String.format("P&L: Rs.%.2f (%.2f%%)\n", h.getPnl(), h.getPnlPercent()));
                data.append(String.format("Invested: Rs.%.2f, Current Value: Rs.%.2f\n",
                        h.getInvestedValue(), h.getCurrentValue()));
                data.append(String.format("Day Change: %.2f%%\n", h.getDayChangePercent()));

                // Technical indicators
                if (h.getEma20() != null) data.append(String.format("EMA20: %.2f, ", h.getEma20()));
                if (h.getEma50() != null) data.append(String.format("EMA50: %.2f, ", h.getEma50()));
                if (h.getEma200() != null) data.append(String.format("EMA200: %.2f", h.getEma200()));
                data.append("\n");
                if (h.getRsi14() != null) data.append(String.format("RSI(14): %.1f\n", h.getRsi14()));
                if (h.getAtr14() != null) data.append(String.format("ATR(14): %.2f\n", h.getAtr14()));
                data.append(String.format("Trend: %s\n", h.getTrendDirection()));

                // Scores & Recommendation
                data.append(String.format("Technical Score: %s, Momentum Score: %s, Overall Score: %s\n",
                        h.getTechnicalScore(), h.getMomentumScore(), h.getOverallScore()));
                data.append(String.format("Recommendation: %s\n", h.getRecommendation()));
                if (h.getAnalysisNotes() != null) data.append("Notes: ").append(h.getAnalysisNotes()).append("\n");

                // Support/Resistance
                if (h.getSupport1() != null) data.append(String.format("Support1: %.2f, ", h.getSupport1()));
                if (h.getSupport2() != null) data.append(String.format("Support2: %.2f", h.getSupport2()));
                data.append("\n");
                if (h.getResistance1() != null) data.append(String.format("Resistance1: %.2f, ", h.getResistance1()));
                if (h.getResistance2() != null) data.append(String.format("Resistance2: %.2f", h.getResistance2()));
                data.append("\n");

                // Valuation from holdings
                if (h.getStockPe() != null) data.append(String.format("Stock PE: %.1f, ", h.getStockPe()));
                if (h.getIndustryPe() != null) data.append(String.format("Industry PE: %.1f, ", h.getIndustryPe()));
                if (h.getPeDeviation() != null) data.append(String.format("PE Deviation: %.1f%%", h.getPeDeviation()));
                data.append("\n");
                if (h.getMarketCap() != null) data.append(String.format("Market Cap: %.0f Cr\n", h.getMarketCap()));

                // Stop loss & targets
                if (h.getSuggestedStopLoss() != null) data.append(String.format("Suggested SL: %.2f\n", h.getSuggestedStopLoss()));
                if (h.getSuggestedTarget1() != null) data.append(String.format("Target1: %.2f\n", h.getSuggestedTarget1()));
                if (h.getSuggestedTarget2() != null) data.append(String.format("Target2: %.2f\n", h.getSuggestedTarget2()));

                data.append("\n");
            }
        } catch (Exception e) {
            log.debug("Stock Research: Holdings data unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendMultibaggerData(StringBuilder data, String symbol) {
        data.append("--- MULTIBAGGER SCREENING (7-DIMENSION ANALYSIS) ---\n");
        try {
            MultibaggerScore score = multibaggerScreenerService.screenSingleStock(symbol);
            if (score != null) {
                data.append(String.format("Composite Score: %d/100, Grade: %s, Verdict: %s\n",
                        score.getCompositeScore(), score.getGrade(), score.getVerdict()));
                data.append(String.format("Market Cap Category: %s\n", score.getMarketCapCategory()));
                data.append("\nDimension Scores:\n");
                // Weights below must match SPEC §12.5 / application.yml. They previously
                // listed the pre-2026-04 seven-dimension split, which was both stale and
                // the source of B-019.
                data.append(String.format("  Technical Momentum (18%%): %s/100\n", dim(score.getTechnicalMomentumScore())));
                data.append(String.format("  Volume Accumulation (12%%): %s/100\n", dim(score.getVolumeAccumulationScore())));
                data.append(String.format("  Relative Strength (12%%): %s/100\n", dim(score.getRelativeStrengthScore())));
                data.append(String.format("  Price Structure (12%%): %s/100\n", dim(score.getPriceStructureScore())));
                data.append(String.format("  Valuation (13%%): %s/100\n", dim(score.getValuationScore())));
                data.append(String.format("  Institutional Interest (10%%): %s/100\n", dim(score.getInstitutionalInterestScore())));
                data.append(String.format("  Sector Tailwind (8%%): %s/100\n", dim(score.getSectorTailwindScore())));
                data.append(String.format("  Financial Quality (15%%): %s/100\n", dim(score.getFinancialQualityScore())));

                // Key metrics
                if (score.getWeeklyRsi() != null)
                    data.append(String.format("\nWeekly RSI: %.1f\n", score.getWeeklyRsi()));
                if (score.getRelativeStrengthVsNifty() != null)
                    data.append(String.format("Relative Strength vs Nifty: %.2f\n", score.getRelativeStrengthVsNifty()));
                if (score.getPeDeviation() != null)
                    data.append(String.format("PE Deviation from Sector: %.1f%%\n", score.getPeDeviation()));
                if (score.getPriceVs52WeekHigh() != null)
                    data.append(String.format("Price vs 52W High: %.1f%%\n", score.getPriceVs52WeekHigh()));
                if (score.getPriceVs52WeekLow() != null)
                    data.append(String.format("Price vs 52W Low: %.1f%%\n", score.getPriceVs52WeekLow()));
                if (score.getAvgVolumeRatio() != null)
                    data.append(String.format("Volume Ratio (recent/avg): %.2f\n", score.getAvgVolumeRatio()));
                if (score.getWeeklyEmaSlope() != null)
                    data.append(String.format("Weekly EMA Slope: %.2f%%\n", score.getWeeklyEmaSlope()));

                // Bullish/Bearish factors
                if (score.getBullishFactors() != null && !score.getBullishFactors().isEmpty()) {
                    data.append("\nBullish Factors:\n");
                    score.getBullishFactors().forEach(f -> data.append("  + ").append(f).append("\n"));
                }
                if (score.getBearishFactors() != null && !score.getBearishFactors().isEmpty()) {
                    data.append("Bearish Factors:\n");
                    score.getBearishFactors().forEach(f -> data.append("  - ").append(f).append("\n"));
                }
            } else {
                data.append("Multibagger screening returned no data for this stock\n");
            }
        } catch (Exception e) {
            data.append("Multibagger screening unavailable: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private void appendValuationData(StringBuilder data, String symbol) {
        data.append("--- VALUATION DATA ---\n");
        try {
            // Strip exchange prefix for valuation service (expects RELIANCE, not NSE:RELIANCE)
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            ValuationData val = valuationService.getValuationData(tradingSymbol);
            if (val != null) {
                data.append(String.format("Industry: %s\n", val.getIndustry()));
                if (val.getStockPe() != null) data.append(String.format("Stock PE: %.1f\n", val.getStockPe()));
                if (val.getIndustryPe() != null) data.append(String.format("Industry PE: %.1f\n", val.getIndustryPe()));
                if (val.getPeDeviation() != null) {
                    data.append(String.format("PE Deviation: %.1f%% (%s)\n",
                            val.getPeDeviation(), valuationService.getValuationInterpretation(val.getStockPe(), val.getIndustryPe())));
                }
                if (val.getMarketCap() != null) data.append(String.format("Market Cap: %.0f Cr\n", val.getMarketCap()));
                if (val.getBookValue() != null) data.append(String.format("Book Value: %.2f\n", val.getBookValue()));
                if (val.getPriceToBook() != null) data.append(String.format("Price to Book: %.2f\n", val.getPriceToBook()));
                if (val.getEps() != null) data.append(String.format("EPS: %.2f\n", val.getEps()));
                if (val.getDividendYield() != null) data.append(String.format("Dividend Yield: %.2f%%\n", val.getDividendYield()));
            } else {
                data.append("Valuation data not available\n");
            }
        } catch (Exception e) {
            data.append("Valuation data unavailable: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private void appendWatchlistData(StringBuilder data, String symbol) {
        try {
            Optional<WatchlistEntity> wl = watchlistRepository.findBySymbol(symbol);
            if (wl.isPresent()) {
                WatchlistEntity w = wl.get();
                data.append("--- WATCHLIST DATA ---\n");
                data.append(String.format("Entry Signal: %s (Confidence: %.0f%%)\n",
                        w.getEntrySignal(), w.getSignalConfidence() != null ? w.getSignalConfidence() : 0));
                data.append(String.format("Trend: %s, Overall Score: %s\n", w.getTrendDirection(), w.getOverallScore()));
                if (w.getSignalReason() != null) data.append("Signal Reason: ").append(w.getSignalReason()).append("\n");
                if (w.getSuggestedEntry() != null) data.append(String.format("Suggested Entry: %.2f\n", w.getSuggestedEntry()));
                if (w.getSuggestedStopLoss() != null) data.append(String.format("Suggested SL: %.2f\n", w.getSuggestedStopLoss()));
                if (w.getSuggestedTarget1() != null) data.append(String.format("Target1: %.2f, ", w.getSuggestedTarget1()));
                if (w.getSuggestedTarget2() != null) data.append(String.format("Target2: %.2f", w.getSuggestedTarget2()));
                data.append("\n");
                if (w.getRiskRewardRatio() != null) data.append(String.format("Risk:Reward: 1:%.1f\n", w.getRiskRewardRatio()));
                if (w.getValuationRating() != null) data.append(String.format("Valuation Rating: %s\n", w.getValuationRating()));
                if (w.getFundamentalScore() != null) data.append(String.format("Fundamental Score: %d/100\n", w.getFundamentalScore()));
                data.append("\n");
            }
        } catch (Exception e) {
            log.debug("Stock Research: Watchlist data unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendScoreTrend(StringBuilder data, String symbol) {
        try {
            List<MultibaggerScoreEntity> history = multibaggerScoreRepository
                    .findHistoryBySymbol(symbol);
            if (history != null && history.size() >= 2) {
                data.append("--- MULTIBAGGER SCORE TREND ---\n");
                history.stream().limit(10).forEach(h ->
                    data.append(String.format("[%s] Score: %d, Grade: %s, Verdict: %s\n",
                            h.getScreeningDate(), h.getCompositeScore(), h.getGrade(), h.getVerdict()))
                );
                // Calculate trend
                int latestScore = history.get(0).getCompositeScore();
                int oldestScore = history.get(history.size() - 1).getCompositeScore();
                int change = latestScore - oldestScore;
                data.append(String.format("Score trend: %s%d over %d data points (%s)\n",
                        change >= 0 ? "+" : "", change, history.size(),
                        change > 5 ? "IMPROVING" : change < -5 ? "DETERIORATING" : "STABLE"));
                data.append("\n");
            }
        } catch (Exception e) {
            log.debug("Stock Research: Score trend unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendSectorFlows(StringBuilder data, String symbol) {
        try {
            // Get the stock's sector from holdings or multibagger data
            String sector = null;
            Optional<HoldingsEntity> holding = holdingsRepository.findBySymbol(symbol);
            if (holding.isPresent() && holding.get().getIndustry() != null) {
                sector = holding.get().getIndustry();
            }

            if (sector != null) {
                LocalDate yesterday = LocalDate.now().minusDays(1);
                // Skip weekends
                while (yesterday.getDayOfWeek() == java.time.DayOfWeek.SATURDAY ||
                       yesterday.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                    yesterday = yesterday.minusDays(1);
                }

                List<SectorFlow> flows = fiiDiiSectorAnalysisService.analyzeSectorFlows(yesterday);
                String finalSector = sector;
                Optional<SectorFlow> sectorFlow = flows.stream()
                        .filter(f -> f.getSector().equalsIgnoreCase(finalSector))
                        .findFirst();

                if (sectorFlow.isPresent()) {
                    SectorFlow f = sectorFlow.get();
                    data.append("--- FII/DII SECTOR FLOWS (").append(sector).append(") ---\n");
                    data.append(String.format("FII Net: %.0f Cr, DII Net: %.0f Cr, Total Net: %.0f Cr\n",
                            f.getFiiNetValue(), f.getDiiNetValue(), f.getTotalNetFlow()));
                    data.append(String.format("Flow Direction: %s\n", f.getFlowDirection()));
                    if (f.getTopBoughtStocks() != null)
                        data.append("Top Bought: ").append(String.join(", ", f.getTopBoughtStocks())).append("\n");
                    if (f.getTopSoldStocks() != null)
                        data.append("Top Sold: ").append(String.join(", ", f.getTopSoldStocks())).append("\n");
                    data.append("\n");
                }
            }
        } catch (Exception e) {
            log.debug("Stock Research: Sector flow data unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendHistoricalAnalysis(StringBuilder data, String symbol) {
        data.append("--- PRICE HISTORY ANALYSIS ---\n");
        try {
            // Get daily candles for last 6 months
            String from = LocalDate.now().minusMonths(6).format(DateTimeFormatter.ISO_DATE);
            String to = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
            List<Map<String, Object>> candles = marketDataService.getRecentCandles(symbol, "day", from, to);

            if (candles != null && candles.size() >= 20) {
                int size = candles.size();

                // Extract key price levels
                double high52w = candles.stream().mapToDouble(c -> toDouble(c.get("high"))).max().orElse(0);
                double low52w = candles.stream().mapToDouble(c -> toDouble(c.get("low"))).min().orElse(0);
                double latestClose = toDouble(candles.get(size - 1).get("close"));

                data.append(String.format("Data points: %d daily candles (6 months)\n", size));
                data.append(String.format("6-Month High: %.2f, Low: %.2f, Current: %.2f\n", high52w, low52w, latestClose));
                data.append(String.format("Position in range: %.1f%% (0%%=low, 100%%=high)\n",
                        high52w > low52w ? ((latestClose - low52w) / (high52w - low52w)) * 100 : 50));

                // Recent price action (last 20 days)
                List<Map<String, Object>> recent20 = candles.subList(Math.max(0, size - 20), size);
                double avg20Volume = recent20.stream().mapToDouble(c -> toDouble(c.get("volume"))).average().orElse(0);
                double latestVolume = toDouble(candles.get(size - 1).get("volume"));
                int greenDays = (int) recent20.stream().filter(c -> toDouble(c.get("close")) > toDouble(c.get("open"))).count();

                data.append(String.format("\nLast 20 days: %d green, %d red\n", greenDays, 20 - greenDays));
                data.append(String.format("Latest Volume: %.0f, 20-day Avg: %.0f, Ratio: %.2f\n",
                        latestVolume, avg20Volume, avg20Volume > 0 ? latestVolume / avg20Volume : 0));

                // Price returns
                if (size >= 5) {
                    double price5dAgo = toDouble(candles.get(size - 5).get("close"));
                    data.append(String.format("5-day return: %.2f%%\n", ((latestClose - price5dAgo) / price5dAgo) * 100));
                }
                if (size >= 20) {
                    double price20dAgo = toDouble(candles.get(size - 20).get("close"));
                    data.append(String.format("20-day return: %.2f%%\n", ((latestClose - price20dAgo) / price20dAgo) * 100));
                }
                if (size >= 60) {
                    double price60dAgo = toDouble(candles.get(size - 60).get("close"));
                    data.append(String.format("3-month return: %.2f%%\n", ((latestClose - price60dAgo) / price60dAgo) * 100));
                }

                // Higher lows pattern check (last 5 swing lows)
                boolean higherLows = checkHigherLows(candles);
                data.append(String.format("Higher lows pattern: %s\n", higherLows ? "YES (bullish structure)" : "NO"));

            } else {
                data.append("Insufficient historical data (need 20+ candles)\n");
            }
        } catch (Exception e) {
            data.append("Historical data unavailable: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private void appendNewsData(StringBuilder data, String symbol) {
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            // Get company name from holdings if available
            String companyName = holdingsRepository.findBySymbol(symbol)
                    .map(h -> h.getTradingSymbol()).orElse(null);

            List<StockNewsService.NewsItem> news = stockNewsService.fetchRecentNews(tradingSymbol, companyName);
            if (!news.isEmpty()) {
                data.append("--- RECENT NEWS (Last 7 Days) ---\n");
                for (StockNewsService.NewsItem item : news) {
                    data.append(String.format("• %s", item.title()));
                    if (item.source() != null) data.append(" [").append(item.source()).append("]");
                    if (item.pubDate() != null) data.append(" (").append(item.pubDate()).append(")");
                    data.append("\n");
                }
                data.append("\n");
            }
        } catch (Exception e) {
            log.debug("Stock Research: News data unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendQuarterlyResults(StringBuilder data, String symbol) {
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            List<NseDataService.QuarterlyResult> results = nseDataService.fetchQuarterlyResults(tradingSymbol);
            if (!results.isEmpty()) {
                data.append("--- QUARTERLY FINANCIAL RESULTS ---\n");
                data.append(String.format("%-25s %-15s %-15s %-10s %-15s\n",
                        "Period", "Revenue (Cr)", "Profit (Cr)", "EPS", "Expenses (Cr)"));
                data.append("-".repeat(80)).append("\n");

                for (NseDataService.QuarterlyResult qr : results) {
                    data.append(String.format("%-25s %-15s %-15s %-10s %-15s\n",
                            qr.getPeriod() != null ? qr.getPeriod() : "N/A",
                            qr.getRevenue() != null ? String.format("%.0f", qr.getRevenue()) : "N/A",
                            qr.getProfit() != null ? String.format("%.0f", qr.getProfit()) : "N/A",
                            qr.getEps() != null ? String.format("%.2f", qr.getEps()) : "N/A",
                            qr.getExpenses() != null ? String.format("%.0f", qr.getExpenses()) : "N/A"));
                }

                // Calculate growth trends
                if (results.size() >= 2) {
                    NseDataService.QuarterlyResult latest = results.get(0);
                    NseDataService.QuarterlyResult previous = results.get(1);
                    if (latest.getRevenue() != null && previous.getRevenue() != null && previous.getRevenue() > 0) {
                        double revenueGrowth = ((latest.getRevenue() - previous.getRevenue()) / previous.getRevenue()) * 100;
                        data.append(String.format("\nQoQ Revenue Growth: %.1f%%\n", revenueGrowth));
                    }
                    if (latest.getProfit() != null && previous.getProfit() != null && previous.getProfit() > 0) {
                        double profitGrowth = ((latest.getProfit() - previous.getProfit()) / previous.getProfit()) * 100;
                        data.append(String.format("QoQ Profit Growth: %.1f%%\n", profitGrowth));
                    }
                }
                if (results.size() >= 4) {
                    NseDataService.QuarterlyResult latest = results.get(0);
                    NseDataService.QuarterlyResult yoy = results.get(3);
                    if (latest.getRevenue() != null && yoy.getRevenue() != null && yoy.getRevenue() > 0) {
                        double yoyGrowth = ((latest.getRevenue() - yoy.getRevenue()) / yoy.getRevenue()) * 100;
                        data.append(String.format("YoY Revenue Growth: %.1f%%\n", yoyGrowth));
                    }
                    if (latest.getProfit() != null && yoy.getProfit() != null && yoy.getProfit() > 0) {
                        double yoyProfitGrowth = ((latest.getProfit() - yoy.getProfit()) / yoy.getProfit()) * 100;
                        data.append(String.format("YoY Profit Growth: %.1f%%\n", yoyProfitGrowth));
                    }
                }

                // Enhanced Earnings Growth Analysis
                try {
                    NseDataService.EarningsGrowthData growth = nseDataService.analyzeEarningsGrowth(tradingSymbol);
                    if (growth != null) {
                        data.append("\nEARNINGS GROWTH ANALYSIS:\n");
                        if (growth.getRevenueCAGR() != null)
                            data.append(String.format("Revenue CAGR (2-year): %.1f%%\n", growth.getRevenueCAGR()));
                        if (growth.getProfitCAGR() != null)
                            data.append(String.format("Profit CAGR (2-year): %.1f%%\n", growth.getProfitCAGR()));
                        if (growth.getLatestOperatingMargin() != null)
                            data.append(String.format("Operating Margin: %.1f%%\n", growth.getLatestOperatingMargin()));
                        if (growth.getLatestNetMargin() != null)
                            data.append(String.format("Net Margin: %.1f%%\n", growth.getLatestNetMargin()));
                        if (growth.getMarginTrend() != null)
                            data.append(String.format("Margin Trend (4Q): %+.1f%% (%s)\n", growth.getMarginTrend(),
                                growth.getMarginTrend() > 0 ? "EXPANDING" : "CONTRACTING"));
                        data.append(String.format("Consecutive Growth Quarters: %d\n", growth.getConsecutiveGrowthQuarters()));
                        data.append(String.format("Earnings Accelerating: %s\n", growth.isEarningsAccelerating() ? "YES" : "NO"));
                        data.append(String.format("Growth Verdict: %s\n", growth.getGrowthVerdict()));
                    }
                } catch (Exception e) {
                    log.debug("Earnings growth analysis unavailable for {}: {}", symbol, e.getMessage());
                }

                data.append("\n");
            }
        } catch (Exception e) {
            log.debug("Stock Research: Quarterly results unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendShareholdingData(StringBuilder data, String symbol) {
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            NseDataService.ShareholdingData sh = nseDataService.fetchShareholding(tradingSymbol);
            if (sh != null) {
                data.append("--- SHAREHOLDING PATTERN ---\n");
                if (sh.getPromoterHolding() != null) data.append(String.format("Promoter Holding: %.2f%%\n", sh.getPromoterHolding()));
                if (sh.getInstitutionalHolding() != null) data.append(String.format("Institutional Holding: %.2f%%\n", sh.getInstitutionalHolding()));
                if (sh.getPublicHolding() != null) data.append(String.format("Public Holding: %.2f%%\n", sh.getPublicHolding()));
                if (sh.getShareholdingDate() != null) data.append("As of: ").append(sh.getShareholdingDate()).append("\n");
                if (sh.getDeliveryPercent() != null) data.append(String.format("Delivery %%: %.2f%%\n", sh.getDeliveryPercent()));
                if (sh.getIndustry() != null) data.append("Industry (NSE): ").append(sh.getIndustry()).append("\n");
                data.append("\n");
            }

            // Historical Shareholding Changes
            try {
                NseDataService.ShareholdingHistory shHistory = nseDataService.fetchShareholdingHistory(tradingSymbol);
                if (shHistory != null && shHistory.getQuarters() != null && shHistory.getQuarters().size() > 1) {
                    data.append("\nSHAREHOLDING TREND (Quarter-over-Quarter):\n");
                    for (NseDataService.ShareholdingQuarter q : shHistory.getQuarters()) {
                        data.append(String.format("  [%s] Promoter: %.2f%%, FII: %.2f%%, DII: %.2f%%, Public: %.2f%%",
                            q.getDate() != null ? q.getDate() : "N/A",
                            q.getPromoterHolding() != null ? q.getPromoterHolding() : 0,
                            q.getFiiHolding() != null ? q.getFiiHolding() : 0,
                            q.getDiiHolding() != null ? q.getDiiHolding() : 0,
                            q.getPublicHolding() != null ? q.getPublicHolding() : 0));
                        if (q.getPledgedPercent() != null && q.getPledgedPercent() > 0) {
                            data.append(String.format(" [Pledge: %.1f%%]", q.getPledgedPercent()));
                        }
                        data.append("\n");
                    }
                    data.append(String.format("\nPromoter Change: %+.2f%% (%s)\n",
                        shHistory.getPromoterChange() != null ? shHistory.getPromoterChange() : 0,
                        shHistory.isPromoterIncreasing() ? "INCREASING" : "DECREASING/STABLE"));
                    if (shHistory.getFiiChange() != null)
                        data.append(String.format("FII Change: %+.2f%% (%s)\n", shHistory.getFiiChange(),
                            shHistory.isFiiIncreasing() ? "INCREASING" : "DECREASING"));
                    data.append(String.format("Insider Signal: %s\n", shHistory.getInsiderSignal()));
                    if (shHistory.getPledgePercent() != null && shHistory.getPledgePercent() > 0)
                        data.append(String.format("Promoter Pledge: %.1f%% (RISK FLAG)\n", shHistory.getPledgePercent()));
                }
            } catch (Exception e) {
                log.debug("Shareholding history unavailable for {}: {}", symbol, e.getMessage());
            }
        } catch (Exception e) {
            log.debug("Stock Research: Shareholding data unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendCorporateAnnouncements(StringBuilder data, String symbol) {
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            List<String> announcements = nseDataService.fetchCorporateAnnouncements(tradingSymbol);
            if (!announcements.isEmpty()) {
                data.append("--- RECENT CORPORATE ANNOUNCEMENTS ---\n");
                announcements.forEach(a -> data.append("• ").append(a).append("\n"));
                data.append("\n");
            }
        } catch (Exception e) {
            log.debug("Stock Research: Announcements unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendPeerComparison(StringBuilder data, String symbol) {
        try {
            // Determine sector from holdings or multibagger data
            String sector = null;
            Optional<HoldingsEntity> holding = holdingsRepository.findBySymbol(symbol);
            if (holding.isPresent() && holding.get().getIndustry() != null) {
                sector = holding.get().getIndustry();
            }
            if (sector == null) {
                // Try from multibagger screening
                MultibaggerScore score = multibaggerScreenerService.screenSingleStock(symbol);
                if (score != null && score.getIndustry() != null) {
                    sector = score.getIndustry();
                }
            }
            if (sector != null) {
                String comparison = peerComparisonService.comparePeers(symbol, sector);
                if (!comparison.isEmpty()) {
                    data.append(comparison);
                }

                // Enhanced peer metrics comparison
                try {
                    PeerComparisonService.PeerComparisonResult metricsResult =
                        peerComparisonService.compareWithMetrics(symbol, sector);
                    if (metricsResult != null && metricsResult.getComparisonText() != null && !metricsResult.getComparisonText().isEmpty()) {
                        data.append(metricsResult.getComparisonText());
                    }
                } catch (Exception e) {
                    log.debug("Enhanced peer comparison unavailable: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Stock Research: Peer comparison unavailable for {}: {}", symbol, e.getMessage());
        }
    }

    private void appendFinancialQualityData(StringBuilder data, String symbol) {
        data.append("--- FINANCIAL QUALITY (BALANCE-SHEET / CASH-FLOW) ---\n");
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            NseDataService.FinancialQualityData fq = nseDataService.analyzeFinancialQuality(tradingSymbol);
            if (fq == null || fq.getQualityScore() == null) {
                data.append("Financial quality analysis unavailable\n\n");
                return;
            }
            data.append(String.format("Quality Score: %d/100  |  Verdict: %s\n",
                    fq.getQualityScore(), fq.getQualityVerdict()));
            if (fq.getInterestCoverageLatest() != null) {
                data.append(String.format("Interest coverage: %.2fx (>3 healthy, <1.5 stressed)\n",
                        fq.getInterestCoverageLatest()));
            }
            if (fq.getFinanceCostToRevenueLatest() != null) {
                data.append(String.format("Finance cost as %% of revenue: %.2f%%\n",
                        fq.getFinanceCostToRevenueLatest()));
            }
            if (fq.getOcfToProfitRatio() != null) {
                data.append(String.format("Cash-flow proxy / profit: %.2f (>=1 healthy, <0.7 accounting-quality concern)\n",
                        fq.getOcfToProfitRatio()));
            }
            if (fq.getPromoterPledgePercent() != null) {
                data.append(String.format("Promoter pledge: %.1f%% (>20%% risky, >50%% red flag)\n",
                        fq.getPromoterPledgePercent()));
            }
            if (fq.getLatestNetMargin() != null) {
                data.append(String.format("Net margin: %.1f%%", fq.getLatestNetMargin()));
                if (fq.getMarginTrend() != null) {
                    data.append(String.format(" (trend %+.1fpp)", fq.getMarginTrend()));
                }
                data.append("\n");
            }
            if (fq.getStrengths() != null && !fq.getStrengths().isEmpty()) {
                data.append("Strengths: ").append(String.join("; ", fq.getStrengths())).append("\n");
            }
            if (fq.getRedFlags() != null && !fq.getRedFlags().isEmpty()) {
                data.append("Red flags: ").append(String.join("; ", fq.getRedFlags())).append("\n");
            }
            data.append(String.format("Data coverage: quarters=%d, debt=%s, cash-flow=%s\n",
                    fq.getQuartersAvailable(),
                    fq.isDebtDataAvailable() ? "yes" : "no",
                    fq.isCashFlowDataAvailable() ? "yes" : "no"));
        } catch (Exception e) {
            data.append("Financial quality fetch failed: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private void appendIntrinsicValuationData(StringBuilder data, String symbol) {
        data.append("--- INTRINSIC VALUATION (REVERSE DCF — SANITY CHECK, NOT PRICE TARGET) ---\n");
        try {
            IntrinsicValuationService.ReverseDcfResult dcf = intrinsicValuationService.analyze(symbol);
            if (dcf == null) {
                data.append("Reverse DCF unavailable\n\n");
                return;
            }
            data.append(String.format("Verdict: %s\n", dcf.getVerdict()));
            if (dcf.getImpliedGrowthPercent() != null) {
                data.append(String.format("Implied growth to justify today's price: %.1f%%/yr for 10 years\n",
                        dcf.getImpliedGrowthPercent()));
            }
            if (dcf.getHistoricalGrowthPercent() != null) {
                data.append(String.format("Actual historical growth (2-yr profit CAGR): %.1f%%/yr\n",
                        dcf.getHistoricalGrowthPercent()));
            }
            if (dcf.getExpectationGapPercent() != null) {
                data.append(String.format("Expectation gap (implied − historical): %+.1fpp\n",
                        dcf.getExpectationGapPercent()));
            }
            if (dcf.getImpliedGrowthAtLowDiscountPercent() != null && dcf.getImpliedGrowthAtHighDiscountPercent() != null) {
                data.append(String.format("Sensitivity band (±200bps discount rate): %.1f%% – %.1f%%\n",
                        dcf.getImpliedGrowthAtHighDiscountPercent(),
                        dcf.getImpliedGrowthAtLowDiscountPercent()));
            }
            if (dcf.getCaveat() != null) {
                data.append("Note: ").append(dcf.getCaveat()).append("\n");
            }
            data.append("Model: 10-yr DCF, discount 12%, terminal growth 4%, FCF proxy = profit + depreciation. ");
            data.append("Known bias: under-values long-duration compounders (IT / platforms / pharma); treat their EXPENSIVE verdict skeptically.\n");
        } catch (Exception e) {
            data.append("Intrinsic valuation fetch failed: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private void appendAnalystSignalData(StringBuilder data, String symbol) {
        data.append("--- ANALYST SIGNAL (TREND-BREAK + BROKERAGE ACTIONS — PROXIES, NOT PAID CONSENSUS) ---\n");
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            AnalystSignalService.AnalystSignal sig = analystSignalService.analyze(tradingSymbol, null);
            if (sig == null) {
                data.append("Analyst signal unavailable\n\n");
                return;
            }
            data.append(String.format("Aggregate verdict: %s (score %+d / 10)%n",
                    sig.getVerdict(), sig.getAggregateScore()));

            NseDataService.EarningsTrendBreakData tb = sig.getTrendBreak();
            if (tb != null && tb.getVerdict() != null) {
                data.append(String.format("Latest quarter (%s) trend-break: %s%n",
                        tb.getLatestPeriod() != null ? tb.getLatestPeriod() : "n/a", tb.getVerdict()));
                if (tb.getActualRevenue() != null && tb.getProjectedRevenue() != null) {
                    data.append(String.format("  Revenue: actual %.0f vs trend-projected %.0f (surprise %+.1f%%)%n",
                            tb.getActualRevenue(), tb.getProjectedRevenue(),
                            tb.getRevenueSurprisePercent() != null ? tb.getRevenueSurprisePercent() : 0));
                }
                if (tb.getActualProfit() != null && tb.getProjectedProfit() != null) {
                    data.append(String.format("  Profit: actual %.0f vs trend-projected %.0f (surprise %+.1f%%)%n",
                            tb.getActualProfit(), tb.getProjectedProfit(),
                            tb.getProfitSurprisePercent() != null ? tb.getProfitSurprisePercent() : 0));
                }
            } else {
                data.append("Trend-break: insufficient quarterly data\n");
            }

            StockNewsService.BrokerageActionData ba = sig.getBrokerageActions();
            if (ba != null) {
                data.append(String.format("Brokerage 7d: %d upgrades, %d downgrades — %s%n",
                        ba.getUpgradeCount(), ba.getDowngradeCount(),
                        ba.getVerdict() != null ? ba.getVerdict() : "NO_COVERAGE"));
                if (ba.getActions() != null) {
                    int shown = 0;
                    for (StockNewsService.BrokerageAction a : ba.getActions()) {
                        if (shown++ >= 5) break; // cap for token budget
                        data.append(String.format("  • [%s] %s — %s%s%n",
                                a.getAction(),
                                a.getBrokerage() != null ? a.getBrokerage() : "unnamed",
                                a.getHeadline() != null ? truncate(a.getHeadline(), 120) : "",
                                a.getTargetPriceInr() != null ? String.format(" (target Rs %.0f)", a.getTargetPriceInr()) : ""));
                    }
                }
            } else {
                data.append("Brokerage scan: no coverage found\n");
            }
            data.append("Methodology: trend-break is a linear projection from 3 prior quarters (proxy for analyst surprise — we do NOT have paid consensus). Brokerage actions are keyword-matched from Google News RSS with strict filters; expect ~30% miss rate and occasional false positives. Treat as supporting evidence, never a sole trigger.\n");
        } catch (Exception e) {
            data.append("Analyst signal fetch failed: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private void appendWealthSignalsData(StringBuilder data, String symbol) {
        data.append("--- WEALTH SIGNALS (LONG-TERM COMPOUNDING QUALITY) ---\n");
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            NseDataService.WealthSignalsData ws = nseDataService.analyzeWealthSignals(tradingSymbol);
            if (ws == null) {
                data.append("Wealth signals unavailable\n\n");
                return;
            }
            data.append(String.format("Gross margin: %s%s (pricing-power proxy; NA for banks/financials)%n",
                    ws.getGrossMarginVerdict() != null ? ws.getGrossMarginVerdict() : "NA",
                    ws.getGrossMarginLatest() != null
                            ? String.format(" — latest %.1f%%%s", ws.getGrossMarginLatest(),
                                ws.getGrossMarginTrend() != null ? String.format(", trend %+.1fpp", ws.getGrossMarginTrend()) : "")
                            : ""));
            data.append(String.format("Earnings consistency: %s%s (steady growth compounds more reliably than lumpy growth)%n",
                    ws.getConsistencyVerdict() != null ? ws.getConsistencyVerdict() : "NA",
                    ws.getEarningsConsistencyScore() != null ? String.format(" — %d/100", ws.getEarningsConsistencyScore()) : ""));
            data.append(String.format("Delivery %%: %s%s (share of volume taken into demat = genuine buyers vs. intraday churn)%n",
                    ws.getDeliveryVerdict() != null ? ws.getDeliveryVerdict() : "NA",
                    ws.getDeliveryPercent() != null ? String.format(" — %.0f%%", ws.getDeliveryPercent()) : ""));
            data.append("Note: PEG (PE / profit-growth%) is reported in the multibagger composite; <1 cheap, >2 expensive relative to growth. ");
            data.append("These signals are positive supporting evidence for a long-term hold; weak readings warrant a closer look but are not sell triggers alone.\n");
        } catch (Exception e) {
            data.append("Wealth signals fetch failed: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    /**
     * Management delivery record (SPEC §34): the measured guidance-met ratio only.
     *
     * <p>Deliberately excludes the AI's reading of transcript tone. Tone is available on
     * {@code POST /api/concall/analyze/{symbol}} for a human to read; feeding it back into
     * another model's analysis would launder an impression into evidence.
     */
    private void appendManagementCredibility(StringBuilder data, String symbol) {
        data.append("--- MANAGEMENT DELIVERY RECORD (WHAT THEY PROMISED VS DELIVERED) ---\n");
        try {
            var c = concallAnalysisService.credibility(symbol);
            switch (c.getStatus()) {
                case "MEASURED" -> data.append(String.format(
                        "Delivered on %d of %d promises that have come due (%.0f%%). %s%n",
                        c.getMet(), c.getResolved(), c.getDeliveryRatioPercent(), c.getExplanation()));
                case "TOO_EARLY" -> data.append("Not enough resolved guidance yet to judge management's "
                        + "delivery record. ").append(c.getExplanation()).append("\n");
                default -> data.append("No earnings-call guidance recorded for this company yet — "
                        + "treat management's claims as unverified rather than as either good or bad.\n");
            }
            data.append("IMPORTANT: absence of a record is NOT a negative signal. It means nobody has "
                    + "checked yet.\n");
        } catch (Exception e) {
            data.append("Management delivery record unavailable: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    /**
     * Capex cycle (SPEC §31): the only block here that LEADS the P&amp;L rather than following
     * it. Rising capital work-in-progress is capacity being built now which cannot show up
     * in revenue for another 12-24 months.
     */
    private void appendCapexCycleData(StringBuilder data, String symbol) {
        data.append("--- CAPEX CYCLE (CAPACITY BEING BUILT — A LEADING SIGNAL) ---\n");
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            NseDataService.CapexCycleData cx = capexCycleService.analyze(symbol, null);
            if (cx == null || !cx.isApplicable()) {
                data.append("Capex-cycle data unavailable")
                    .append(cx != null && cx.getReason() != null ? " (" + cx.getReason() + ")" : "")
                    .append("\n\n");
                return;
            }
            data.append(String.format("Verdict: %s — %s%n", cx.getVerdict(), cx.getReason()));
            if (cx.getFinancialYear() != null) {
                data.append("Source: annual Ind-AS filing ").append(cx.getFinancialYear()).append("\n");
            }
            if (cx.getCwipIntensityPercent() != null) {
                data.append(String.format("CWIP intensity: %.1f%% of net block — how large the build is versus the plant already running%n",
                        cx.getCwipIntensityPercent()));
            }
            if (cx.getCwipCurrent() != null) {
                data.append(String.format("Capital work-in-progress: Rs %.0f cr", cx.getCwipCurrent()));
                if (cx.getCwipPrior() != null) {
                    data.append(String.format(" (prior year Rs %.0f cr, change Rs %+.0f cr)",
                            cx.getCwipPrior(), cx.getCwipChange()));
                } else {
                    data.append(" (no prior-year comparative in the filing — direction unknown)");
                }
                data.append("\n");
            }
            if (cx.getCapexToDepreciation() != null) {
                data.append(String.format("Capex/depreciation: %.2fx — above 1 the asset base is growing, well below 1 the company is harvesting%n",
                        cx.getCapexToDepreciation()));
            }
            data.append("IMPORTANT: this is a leading indicator, not a guarantee. Capacity can be built and go unused, and "
                    + "debt-funded expansion on a weak balance sheet is a risk rather than a strength — cross-check it "
                    + "against the CAPITAL EFFICIENCY and FINANCIAL QUALITY blocks before treating it as bullish.\n");
        } catch (Exception e) {
            data.append("Capex cycle fetch failed: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private void appendCapitalEfficiencyData(StringBuilder data, String symbol) {
        data.append("--- CAPITAL EFFICIENCY (ANNUAL BALANCE SHEET — THE CORE WEALTH METRICS) ---\n");
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            NseDataService.CapitalEfficiencyData ce = nseDataService.analyzeCapitalEfficiency(tradingSymbol, null);
            if (ce == null || !ce.isApplicable()) {
                data.append("Capital-efficiency data unavailable")
                    .append(ce != null && ce.getNaReason() != null ? " (" + ce.getNaReason() + ")" : "")
                    .append("\n\n");
                return;
            }
            data.append(String.format("Source: annual Ind-AS filing %s (%s)%n",
                    ce.getFinancialYear() != null ? ce.getFinancialYear() : "latest",
                    ce.isConsolidated() ? "Consolidated" : "Standalone"));
            data.append(String.format("Overall: %s%s%n", ce.getOverallVerdict(),
                    ce.isFinancialSector() ? " (bank/financial — ROCE & D/E not directly comparable)" : ""));
            if (ce.getRocePercent() != null) {
                data.append(String.format("ROCE: %.1f%% (%s) — return on total capital; >15%% good, >20%% excellent%n",
                        ce.getRocePercent(), ce.getRoceVerdict()));
            }
            if (ce.getRoePercent() != null) {
                data.append(String.format("ROE: %.1f%% (%s) — return on shareholder equity; >15%% good%n",
                        ce.getRoePercent(), ce.getRoeVerdict()));
            }
            if (ce.getRoaPercent() != null && ce.isFinancialSector()) {
                data.append(String.format("ROA: %.2f%% (%s) — return on assets; the headline metric for banks (>1.5%% good)%n",
                        ce.getRoaPercent(), ce.getRoaVerdict()));
            }
            if (ce.getDebtToEquity() != null) {
                data.append(String.format("Debt-to-Equity: %.2f (%s) — <0.3 very safe, >2 risky%n",
                        ce.getDebtToEquity(), ce.getLeverageVerdict()));
            }
            if (ce.getCashConversionRatio() != null) {
                data.append(String.format("Cash conversion: %.2fx (%s) — real operating cash flow / profit; >=0.8 means profits are real cash%n",
                        ce.getCashConversionRatio(), ce.getCashConversionVerdict()));
            }
            if (ce.getDividendPayoutPercent() != null) {
                data.append(String.format("Dividend payout: %.0f%% of profit%n", ce.getDividendPayoutPercent()));
            }
            if (ce.getStrengths() != null && !ce.getStrengths().isEmpty()) {
                data.append("Strengths: ").append(String.join("; ", ce.getStrengths())).append("\n");
            }
            if (ce.getRedFlags() != null && !ce.getRedFlags().isEmpty()) {
                data.append("Concerns: ").append(String.join("; ", ce.getRedFlags())).append("\n");
            }
            data.append("These are the strongest long-term wealth signals — sustained high ROCE/ROE with low debt and strong cash conversion is the multibagger profile. Annual cadence (one filing/year).\n");
        } catch (Exception e) {
            data.append("Capital efficiency fetch failed: ").append(e.getMessage()).append("\n");
        }
        data.append("\n");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    // ============================================================
    // Utility Methods
    // ============================================================

    private double toDouble(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(value.toString()); } catch (Exception e) { return 0; }
    }

    private boolean checkHigherLows(List<Map<String, Object>> candles) {
        // Simple higher lows check: compare lows at 20%, 40%, 60%, 80% of the range
        int size = candles.size();
        if (size < 20) return false;

        int step = size / 5;
        double[] lows = new double[4];
        for (int i = 0; i < 4; i++) {
            int start = i * step;
            int end = Math.min(start + step, size);
            lows[i] = candles.subList(start, end).stream()
                    .mapToDouble(c -> toDouble(c.get("low"))).min().orElse(0);
        }

        // Check if each low is higher than the previous
        return lows[1] > lows[0] && lows[2] > lows[1] && lows[3] > lows[2];
    }

    /**
     * Render a nullable dimension score for the AI prompt. Says "not measurable" outright
     * rather than emitting the literal "null", which the model would be left to interpret —
     * and never substitutes a number, which would invent a signal that does not exist.
     */
    private static String dim(Integer score) {
        return score == null ? "not measurable" : String.valueOf(score);
    }
}
