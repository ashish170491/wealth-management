package com.example.trading.ai;

import com.example.trading.marketdata.MarketDataService;
import com.example.trading.multibagger.MultibaggerScore;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI-powered stock discovery service.
 * Uses AI to suggest new investment themes, identify overlooked stocks,
 * and find contrarian opportunities beyond the existing screening universe.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockDiscoveryService {

    private final AiService aiService;
    private final MultibaggerScreenerService multibaggerScreenerService;
    private final HoldingsRepository holdingsRepository;
    private final StockResearchService stockResearchService;
    private final MarketDataService marketDataService;

    private static final Pattern NSE_SYMBOL_PATTERN = Pattern.compile("NSE:[A-Z][A-Z0-9&-]+");

    private static final List<String> ALL_INDIAN_SECTORS = List.of(
        "Banking", "IT", "Pharma", "FMCG", "Auto", "Metals", "Energy", "Power",
        "Defence", "Capital Goods", "Real Estate", "Infrastructure", "Chemicals",
        "Consumer", "Telecom", "Cement", "Finance", "Insurance", "Media",
        "Hospitality", "Textiles", "Agriculture", "Logistics", "Healthcare",
        "Education", "Retail", "Aviation"
    );

    private static final String DISCOVERY_SYSTEM_PROMPT =
        "You are a senior equity research analyst at a leading Indian brokerage. " +
        "Your job is to discover investment opportunities that others are missing. " +
        "You have deep knowledge of Indian markets, sectors, and listed companies. " +
        "Be specific — name exact NSE-listed stock symbols (e.g., NSE:RELIANCE). " +
        "Focus on stocks listed on NSE India. Use plain text, no markdown formatting.";

    /**
     * AI discovers investment themes and suggests stocks based on current portfolio and market context.
     *
     * @return AI-generated discovery report with themes and stock suggestions
     */
    public String discoverOpportunities() {
        if (!aiService.isAvailable()) return "";

        log.info("Stock Discovery: Running AI-powered opportunity discovery...");

        StringBuilder context = new StringBuilder();
        context.append("=== STOCK DISCOVERY REQUEST ===\n\n");

        // Current portfolio context
        appendPortfolioContext(context);

        // Current screening universe overview
        appendScreeningOverview(context);

        context.append("\n=== DISCOVERY TASKS ===\n");
        context.append("Based on the above portfolio and market data:\n\n");
        context.append("1. EMERGING THEMES: Identify 3-4 investment themes/sectors showing early momentum in Indian markets right now ");
        context.append("(e.g., defense, green energy, EMS, specialty chemicals). For each theme, name 2-3 specific NSE-listed stocks.\n\n");
        context.append("2. OVERLOOKED GEMS: Suggest 5 specific NSE-listed stocks that are NOT in our screening universe ");
        context.append("but show strong potential. Focus on mid-caps and small-caps with catalysts.\n\n");
        context.append("3. CONTRARIAN IDEAS: Identify 3 beaten-down stocks or out-of-favor sectors that could be turnaround candidates. ");
        context.append("Explain what reversal catalyst to watch for.\n\n");
        context.append("4. PORTFOLIO GAPS: Looking at the current holdings, identify which sectors/themes are ");
        context.append("underrepresented and suggest specific stocks to fill those gaps.\n\n");
        context.append("5. RISK ALERT: Flag any concentration risks or macro threats to the current portfolio.\n\n");
        context.append("6. UNIVERSE EXPANSION: Suggest 10 specific NSE-listed stocks that should be added to our screening universe of ~90 stocks. ");
        context.append("For each, provide the exact NSE symbol (e.g., NSE:SYMBOL), sector, approximate market cap, and why it deserves screening. ");
        context.append("Focus on stocks with >Rs.5000 Cr market cap that are actively traded.\n\n");
        context.append("For each stock suggestion, provide: Symbol, Current approximate price range, Why it's interesting, ");
        context.append("Key catalyst, and Risk level (Low/Medium/High).\n");

        String analysis = aiService.analyze(DISCOVERY_SYSTEM_PROMPT, context.toString());

        log.info("Stock Discovery: Analysis complete ({} chars)", analysis != null ? analysis.length() : 0);
        return analysis != null ? analysis : "";
    }

    /**
     * Research stocks suggested by the discovery service.
     * Takes AI's suggestions and runs deep research on each one.
     *
     * @param symbols List of symbols to research (e.g., ["NSE:RELIANCE", "NSE:TATAPOWER"])
     * @return Map of symbol to research analysis
     */
    public Map<String, String> researchSuggestedStocks(List<String> symbols) {
        return symbols.stream()
                .limit(5) // Max 5 to control API costs
                .collect(Collectors.toMap(
                        symbol -> symbol,
                        symbol -> {
                            try {
                                return stockResearchService.researchStock(symbol);
                            } catch (Exception e) {
                                log.debug("Research failed for {}: {}", symbol, e.getMessage());
                                return "Research unavailable for " + symbol;
                            }
                        }
                ));
    }

    private void appendPortfolioContext(StringBuilder context) {
        try {
            List<HoldingsEntity> holdings = holdingsRepository.findAll();
            if (!holdings.isEmpty()) {
                context.append("--- CURRENT PORTFOLIO ---\n");
                context.append(String.format("Total holdings: %d stocks\n", holdings.size()));

                // Group by sector
                Map<String, List<HoldingsEntity>> bySector = holdings.stream()
                        .filter(h -> h.getIndustry() != null)
                        .collect(Collectors.groupingBy(HoldingsEntity::getIndustry));

                context.append("Sector allocation:\n");
                bySector.forEach((sector, stocks) -> {
                    double sectorValue = stocks.stream().mapToDouble(HoldingsEntity::getCurrentValue).sum();
                    context.append(String.format("  %s: %d stocks (Rs.%.0f)\n", sector, stocks.size(), sectorValue));
                });

                // Top holdings by value
                context.append("\nTop holdings:\n");
                holdings.stream()
                        .sorted((a, b) -> Double.compare(b.getCurrentValue(), a.getCurrentValue()))
                        .limit(10)
                        .forEach(h -> context.append(String.format("  %s: Rs.%.0f (P&L: %.1f%%, Trend: %s, Score: %s)\n",
                                h.getSymbol(), h.getCurrentValue(), h.getPnlPercent(),
                                h.getTrendDirection(), h.getOverallScore())));

                // Portfolio health
                long bullish = holdings.stream().filter(h -> "BULLISH".equals(h.getTrendDirection())).count();
                long bearish = holdings.stream().filter(h -> "BEARISH".equals(h.getTrendDirection())).count();
                double totalPnl = holdings.stream().mapToDouble(HoldingsEntity::getPnl).sum();
                context.append(String.format("\nPortfolio: %d bullish, %d bearish, Total P&L: Rs.%.0f\n",
                        bullish, bearish, totalPnl));

                context.append("\n");
            }
        } catch (Exception e) {
            log.debug("Portfolio context unavailable: {}", e.getMessage());
        }
    }

    private void appendScreeningOverview(StringBuilder context) {
        try {
            List<MultibaggerScore> scores = multibaggerScreenerService.getLatestScores();
            if (scores != null && !scores.isEmpty()) {
                context.append("--- CURRENT SCREENING UNIVERSE ---\n");
                context.append(String.format("Total stocks screened: %d\n", scores.size()));

                long strongMB = scores.stream().filter(s -> "STRONG_MULTIBAGGER".equals(s.getVerdict())).count();
                long potentialMB = scores.stream().filter(s -> "POTENTIAL_MULTIBAGGER".equals(s.getVerdict())).count();
                long avoid = scores.stream().filter(s -> "AVOID".equals(s.getVerdict())).count();

                context.append(String.format("Strong Multibagger: %d, Potential: %d, Avoid: %d\n",
                        strongMB, potentialMB, avoid));

                // Top 5 by score
                context.append("Top 5 by multibagger score:\n");
                scores.stream().limit(5).forEach(s ->
                    context.append(String.format("  %s: Score=%d, Grade=%s, Sector=%s\n",
                            s.getSymbol(), s.getCompositeScore(), s.getGrade(), s.getIndustry())));

                // Sector distribution
                Map<String, Long> sectorCount = scores.stream()
                        .filter(s -> s.getIndustry() != null)
                        .collect(Collectors.groupingBy(MultibaggerScore::getIndustry, Collectors.counting()));
                context.append("\nSectors in universe: ").append(String.join(", ", sectorCount.keySet())).append("\n");
                context.append("Stocks listed in universe: ");
                context.append(scores.stream().map(MultibaggerScore::getSymbol).collect(Collectors.joining(", ")));
                context.append("\n\n");
            }
        } catch (Exception e) {
            log.debug("Screening overview unavailable: {}", e.getMessage());
        }
    }

    // ========== Systematic Discovery & Universe Expansion ==========

    /**
     * Systematic (non-AI) scanning for stocks that should be in the screening universe.
     * Finds stocks in holdings that are NOT in the multibagger screening universe.
     */
    public List<DiscoveredStock> discoverSystematically() {
        log.info("Stock Discovery: Running systematic scan for universe gaps...");

        List<DiscoveredStock> discovered = new ArrayList<>();
        Set<String> universeSet = new HashSet<>(multibaggerScreenerService.getScreeningUniverse());

        // Check holdings for stocks not in the screening universe
        try {
            List<HoldingsEntity> holdings = holdingsRepository.findAll();
            for (HoldingsEntity holding : holdings) {
                String symbol = holding.getSymbol();
                if (symbol != null && !universeSet.contains(symbol)) {
                    DiscoveredStock stock = new DiscoveredStock();
                    stock.setSymbol(symbol);
                    stock.setSource("HOLDINGS");
                    stock.setReason("Stock is in portfolio holdings but not in screening universe");
                    stock.setCurrentPrice(holding.getCurrentPrice());
                    stock.setSector(holding.getIndustry());
                    discovered.add(stock);
                }
            }
        } catch (Exception e) {
            log.warn("Systematic discovery - holdings check failed: {}", e.getMessage());
        }

        log.info("Stock Discovery: Systematic scan found {} stocks not in universe", discovered.size());
        return discovered;
    }

    /**
     * Suggests universe expansion by combining systematic discovery with AI-powered suggestions.
     * Calls AI discovery, parses symbols from the response, filters out stocks already in universe.
     */
    public UniverseExpansionResult suggestUniverseExpansion() {
        log.info("Stock Discovery: Building universe expansion suggestions...");

        UniverseExpansionResult result = new UniverseExpansionResult();
        List<String> universe = multibaggerScreenerService.getScreeningUniverse();
        Set<String> universeSet = new HashSet<>(universe);
        result.setCurrentUniverseSize(universeSet.size());

        // 1. Systematic: holdings not in universe
        result.setNewStocksFromHoldings(discoverSystematically());

        // 2. AI-powered discovery
        List<DiscoveredStock> aiStocks = new ArrayList<>();
        String aiAnalysis = "";
        try {
            aiAnalysis = discoverOpportunities();
            result.setAiAnalysis(aiAnalysis);

            if (aiAnalysis != null && !aiAnalysis.isEmpty()) {
                // Extract NSE:SYMBOL patterns from AI response
                Matcher matcher = NSE_SYMBOL_PATTERN.matcher(aiAnalysis);
                Set<String> seenSymbols = new HashSet<>();

                while (matcher.find()) {
                    String symbol = matcher.group();
                    // Filter out duplicates and stocks already in universe
                    if (!universeSet.contains(symbol) && seenSymbols.add(symbol)) {
                        DiscoveredStock stock = new DiscoveredStock();
                        stock.setSymbol(symbol);
                        stock.setSource("AI_DISCOVERY");
                        stock.setReason("AI-suggested stock not currently in screening universe");

                        // Try to fetch current price
                        try {
                            Double price = marketDataService.getCurrentPrice(symbol);
                            stock.setCurrentPrice(price);
                        } catch (Exception e) {
                            log.debug("Could not fetch price for AI-suggested {}: {}", symbol, e.getMessage());
                        }

                        aiStocks.add(stock);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("AI discovery failed: {}", e.getMessage());
            result.setAiAnalysis("AI discovery unavailable: " + e.getMessage());
        }
        result.setAiSuggestedStocks(aiStocks);

        // 3. Sector gap analysis
        result.setUnderrepresentedSectors(findUnderrepresentedSectors());

        log.info("Stock Discovery: Universe expansion - {} from holdings, {} from AI, {} underrepresented sectors",
                result.getNewStocksFromHoldings().size(), result.getAiSuggestedStocks().size(),
                result.getUnderrepresentedSectors().size());

        return result;
    }

    /**
     * Finds sectors that are underrepresented (0-1 stocks) in the screening universe.
     */
    private List<String> findUnderrepresentedSectors() {
        List<String> underrepresented = new ArrayList<>();

        try {
            List<MultibaggerScore> scores = multibaggerScreenerService.getLatestScores();

            // Count stocks per sector in current universe
            Map<String, Long> sectorCounts = new HashMap<>();
            if (scores != null) {
                sectorCounts = scores.stream()
                        .filter(s -> s.getIndustry() != null && !s.getIndustry().isEmpty())
                        .collect(Collectors.groupingBy(
                                s -> s.getIndustry().toLowerCase().trim(),
                                Collectors.counting()));
            }

            // Check each broad Indian sector against universe coverage
            for (String sector : ALL_INDIAN_SECTORS) {
                String sectorLower = sector.toLowerCase();
                long count = sectorCounts.entrySet().stream()
                        .filter(e -> e.getKey().contains(sectorLower) || sectorLower.contains(e.getKey()))
                        .mapToLong(Map.Entry::getValue)
                        .sum();

                if (count <= 1) {
                    underrepresented.add(sector + (count == 0 ? " (no stocks)" : " (1 stock only)"));
                }
            }
        } catch (Exception e) {
            log.warn("Sector gap analysis failed: {}", e.getMessage());
        }

        return underrepresented;
    }

    /**
     * Generates a formatted text report combining systematic + AI discovery + sector gaps.
     */
    public String getUniverseExpansionReport() {
        UniverseExpansionResult result = suggestUniverseExpansion();
        StringBuilder report = new StringBuilder();

        report.append("=== UNIVERSE EXPANSION REPORT ===\n");
        report.append(String.format("Current screening universe: %d stocks\n\n", result.getCurrentUniverseSize()));

        // Holdings not in universe
        report.append("--- HOLDINGS NOT IN SCREENING UNIVERSE ---\n");
        if (result.getNewStocksFromHoldings().isEmpty()) {
            report.append("All holdings are already in the screening universe.\n");
        } else {
            for (DiscoveredStock stock : result.getNewStocksFromHoldings()) {
                report.append(String.format("  %s | Sector: %s | Price: %s | Source: %s\n",
                        stock.getSymbol(),
                        stock.getSector() != null ? stock.getSector() : "Unknown",
                        stock.getCurrentPrice() != null ? String.format("Rs.%.2f", stock.getCurrentPrice()) : "N/A",
                        stock.getSource()));
                report.append(String.format("    Reason: %s\n", stock.getReason()));
            }
        }

        // AI-suggested stocks
        report.append("\n--- AI-SUGGESTED NEW STOCKS ---\n");
        if (result.getAiSuggestedStocks().isEmpty()) {
            report.append("No new AI suggestions available.\n");
        } else {
            for (DiscoveredStock stock : result.getAiSuggestedStocks()) {
                report.append(String.format("  %s | Price: %s\n",
                        stock.getSymbol(),
                        stock.getCurrentPrice() != null ? String.format("Rs.%.2f", stock.getCurrentPrice()) : "N/A"));
                report.append(String.format("    Reason: %s\n", stock.getReason()));
            }
        }

        // Underrepresented sectors
        report.append("\n--- UNDERREPRESENTED SECTORS ---\n");
        if (result.getUnderrepresentedSectors().isEmpty()) {
            report.append("All major sectors are well-represented.\n");
        } else {
            for (String sector : result.getUnderrepresentedSectors()) {
                report.append(String.format("  - %s\n", sector));
            }
        }

        // AI analysis summary
        if (result.getAiAnalysis() != null && !result.getAiAnalysis().isEmpty()) {
            report.append("\n--- AI ANALYSIS ---\n");
            report.append(result.getAiAnalysis());
            report.append("\n");
        }

        return report.toString();
    }

    // ========== DTOs ==========

    @Data
    public static class DiscoveredStock {
        private String symbol;
        private String source;      // "HOLDINGS", "AI_DISCOVERY", "SECTOR_GAP"
        private String reason;
        private Double currentPrice;
        private String sector;
    }

    @Data
    public static class UniverseExpansionResult {
        private List<DiscoveredStock> newStocksFromHoldings;
        private List<DiscoveredStock> aiSuggestedStocks;
        private List<String> underrepresentedSectors;
        private int currentUniverseSize;
        private String aiAnalysis;
    }
}
