package com.example.trading.ai;

import com.example.trading.ai.NseDataService;
import com.example.trading.ai.NseDataService.QuarterlyResult;
import com.example.trading.holdings.StockValuationService;
import com.example.trading.holdings.StockValuationService.ValuationData;
import com.example.trading.multibagger.MultibaggerScore;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.watchlist.WatchlistEntity;
import com.example.trading.watchlist.WatchlistRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compares a stock against its sector peers using data from
 * MultibaggerScreener, Holdings, and Watchlist services.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PeerComparisonService {

    private final MultibaggerScreenerService multibaggerScreenerService;
    private final HoldingsRepository holdingsRepository;
    private final WatchlistRepository watchlistRepository;
    private final StockValuationService stockValuationService;
    private final NseDataService nseDataService;

    /**
     * Get peer comparison data for a stock.
     * Finds stocks in the same sector and compares key metrics.
     *
     * @param symbol Stock symbol (NSE:RELIANCE)
     * @param sector Sector/Industry name
     * @return Formatted peer comparison text
     */
    public String comparePeers(String symbol, String sector) {
        if (sector == null || sector.isEmpty()) return "";

        StringBuilder comparison = new StringBuilder();
        try {
            // Get all screened stocks
            List<MultibaggerScore> allScores = multibaggerScreenerService.getLatestScores();
            if (allScores == null || allScores.isEmpty()) return "";

            // Find peers in same sector (case-insensitive partial match)
            String sectorLower = sector.toLowerCase();
            List<MultibaggerScore> peers = allScores.stream()
                    .filter(s -> s.getIndustry() != null && s.getIndustry().toLowerCase().contains(sectorLower))
                    .filter(s -> !s.getSymbol().equals(symbol))
                    .sorted((a, b) -> Integer.compare(b.getCompositeScore(), a.getCompositeScore()))
                    .limit(5)
                    .collect(Collectors.toList());

            // Also find the target stock's score
            MultibaggerScore targetScore = allScores.stream()
                    .filter(s -> s.getSymbol().equals(symbol))
                    .findFirst().orElse(null);

            if (peers.isEmpty()) {
                // Try broader match
                peers = allScores.stream()
                        .filter(s -> s.getIndustry() != null &&
                                (s.getIndustry().toLowerCase().contains(sectorLower.split(" ")[0]) ||
                                 sectorLower.contains(s.getIndustry().toLowerCase().split(" ")[0])))
                        .filter(s -> !s.getSymbol().equals(symbol))
                        .sorted((a, b) -> Integer.compare(b.getCompositeScore(), a.getCompositeScore()))
                        .limit(5)
                        .collect(Collectors.toList());
            }

            if (peers.isEmpty()) return "";

            // Fetch valuation and earnings data for enhanced columns
            ValuationData targetValuation = safeGetValuation(symbol);
            List<QuarterlyResult> targetQuarterly = safeGetQuarterly(symbol);
            Double targetPE = targetValuation != null ? targetValuation.getStockPe() : null;
            Double targetYoyRev = computeYoyRevenueGrowth(targetQuarterly);
            Double targetMargin = computeNetMargin(targetQuarterly);

            comparison.append("--- PEER COMPARISON (").append(sector).append(") ---\n");
            comparison.append(String.format("%-20s %-8s %-8s %-8s %-10s %-9s %-8s %-10s\n",
                    "Stock", "Score", "Grade", "PE", "YoY Rev%", "Margin%", "RS", "Verdict"));
            comparison.append("-".repeat(95)).append("\n");

            // Target stock first
            if (targetScore != null) {
                comparison.append(String.format("%-20s %-8d %-8s %-8s %-10s %-9s %-8d %-10s  <-- THIS STOCK\n",
                        truncate(targetScore.getSymbol(), 20), targetScore.getCompositeScore(), targetScore.getGrade(),
                        formatDouble(targetPE, "%.1f"), formatPercent(targetYoyRev), formatPercent(targetMargin),
                        targetScore.getRelativeStrengthScore(), targetScore.getVerdict()));
            }

            // Track PE and growth for sector summary
            List<Double> sectorPEs = new ArrayList<>();
            List<Double> sectorGrowths = new ArrayList<>();
            if (targetPE != null && targetPE > 0) sectorPEs.add(targetPE);
            if (targetYoyRev != null) sectorGrowths.add(targetYoyRev);

            String bestGrower = null;
            double bestGrowth = Double.NEGATIVE_INFINITY;
            String mostUndervalued = null;
            double lowestPEWithGrowth = Double.MAX_VALUE;

            // Peers
            for (MultibaggerScore peer : peers) {
                ValuationData peerValuation = safeGetValuation(peer.getSymbol());
                List<QuarterlyResult> peerQuarterly = safeGetQuarterly(peer.getSymbol());
                Double peerPE = peerValuation != null ? peerValuation.getStockPe() : null;
                Double peerYoyRev = computeYoyRevenueGrowth(peerQuarterly);
                Double peerMargin = computeNetMargin(peerQuarterly);

                comparison.append(String.format("%-20s %-8d %-8s %-8s %-10s %-9s %-8d %-10s\n",
                        truncate(peer.getSymbol(), 20), peer.getCompositeScore(), peer.getGrade(),
                        formatDouble(peerPE, "%.1f"), formatPercent(peerYoyRev), formatPercent(peerMargin),
                        peer.getRelativeStrengthScore(), peer.getVerdict()));

                if (peerPE != null && peerPE > 0) sectorPEs.add(peerPE);
                if (peerYoyRev != null) sectorGrowths.add(peerYoyRev);

                // Track best grower
                if (peerYoyRev != null && peerYoyRev > bestGrowth) {
                    bestGrowth = peerYoyRev;
                    bestGrower = cleanSymbolName(peer.getSymbol());
                }
                // Track most undervalued (lowest PE with positive growth)
                if (peerPE != null && peerPE > 0 && peerYoyRev != null && peerYoyRev > 0 && peerPE < lowestPEWithGrowth) {
                    lowestPEWithGrowth = peerPE;
                    mostUndervalued = cleanSymbolName(peer.getSymbol());
                }
            }

            // Check target stock for best grower / most undervalued
            if (targetYoyRev != null && targetYoyRev > bestGrowth) {
                bestGrowth = targetYoyRev;
                bestGrower = cleanSymbolName(symbol);
            }
            if (targetPE != null && targetPE > 0 && targetYoyRev != null && targetYoyRev > 0 && targetPE < lowestPEWithGrowth) {
                lowestPEWithGrowth = targetPE;
                mostUndervalued = cleanSymbolName(symbol);
            }

            // Ranking within sector
            if (targetScore != null) {
                List<MultibaggerScore> allSectorStocks = new ArrayList<>(peers);
                allSectorStocks.add(targetScore);
                allSectorStocks.sort((a, b) -> Integer.compare(b.getCompositeScore(), a.getCompositeScore()));
                int rank = allSectorStocks.indexOf(targetScore) + 1;
                comparison.append(String.format("\nSector Rank: #%d out of %d peers\n", rank, allSectorStocks.size()));

                // Compare vs sector average
                double avgScore = allSectorStocks.stream().mapToInt(MultibaggerScore::getCompositeScore).average().orElse(0);
                comparison.append(String.format("Sector Avg Score: %.0f vs This Stock: %d (%s)\n",
                        avgScore, targetScore.getCompositeScore(),
                        targetScore.getCompositeScore() > avgScore ? "ABOVE AVERAGE" : "BELOW AVERAGE"));
            }

            // Sector PE summary
            if (!sectorPEs.isEmpty()) {
                double sectorAvgPE = sectorPEs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                comparison.append(String.format("\nSector Avg PE: %.1f", sectorAvgPE));
                if (targetPE != null && targetPE > 0) {
                    String premium = targetPE > sectorAvgPE ? "PREMIUM TO SECTOR" : "DISCOUNT TO SECTOR";
                    comparison.append(String.format(" | This Stock PE: %.1f (%s)", targetPE, premium));
                }
                comparison.append("\n");
            }

            // Best grower
            if (bestGrower != null && bestGrowth > Double.NEGATIVE_INFINITY) {
                comparison.append(String.format("Best Grower: %s (%+.1f%% YoY revenue)\n", bestGrower, bestGrowth));
            }

            // Most undervalued
            if (mostUndervalued != null && lowestPEWithGrowth < Double.MAX_VALUE) {
                Double muGrowth = mostUndervalued.equals(cleanSymbolName(symbol)) ? targetYoyRev :
                        sectorGrowths.isEmpty() ? null : sectorGrowths.get(sectorGrowths.size() - 1);
                comparison.append(String.format("Most Undervalued: %s (PE %.1f with %s growth)\n",
                        mostUndervalued, lowestPEWithGrowth, muGrowth != null ? String.format("%+.1f%%", muGrowth) : "positive"));
            }

            // Check if any peers are in holdings
            List<HoldingsEntity> allHoldings = holdingsRepository.findAll();
            List<String> heldPeers = peers.stream()
                    .filter(p -> allHoldings.stream().anyMatch(h -> h.getSymbol().equals(p.getSymbol())))
                    .map(MultibaggerScore::getSymbol)
                    .collect(Collectors.toList());
            if (!heldPeers.isEmpty()) {
                comparison.append("Peers you also own: ").append(String.join(", ", heldPeers)).append("\n");
            }

            comparison.append("\n");
        } catch (Exception e) {
            log.debug("Peer comparison failed for {} in {}: {}", symbol, sector, e.getMessage());
        }
        return comparison.toString();
    }

    /**
     * Comprehensive peer comparison with valuation, growth, and technical metrics.
     * Returns a structured result for programmatic use and AI consumption.
     *
     * @param symbol Stock symbol (NSE:RELIANCE)
     * @param sector Sector/Industry name
     * @return PeerComparisonResult with detailed metrics, or null if data unavailable
     */
    public PeerComparisonResult compareWithMetrics(String symbol, String sector) {
        if (sector == null || sector.isEmpty()) return null;

        try {
            List<MultibaggerScore> allScores = multibaggerScreenerService.getLatestScores();
            if (allScores == null || allScores.isEmpty()) return null;

            String sectorLower = sector.toLowerCase();

            // Find peers in same sector
            List<MultibaggerScore> peerScores = allScores.stream()
                    .filter(s -> s.getIndustry() != null && s.getIndustry().toLowerCase().contains(sectorLower))
                    .filter(s -> !s.getSymbol().equals(symbol))
                    .sorted((a, b) -> Integer.compare(b.getCompositeScore(), a.getCompositeScore()))
                    .limit(8)
                    .collect(Collectors.toList());

            // Broader match fallback
            if (peerScores.isEmpty()) {
                peerScores = allScores.stream()
                        .filter(s -> s.getIndustry() != null &&
                                (s.getIndustry().toLowerCase().contains(sectorLower.split(" ")[0]) ||
                                 sectorLower.contains(s.getIndustry().toLowerCase().split(" ")[0])))
                        .filter(s -> !s.getSymbol().equals(symbol))
                        .sorted((a, b) -> Integer.compare(b.getCompositeScore(), a.getCompositeScore()))
                        .limit(8)
                        .collect(Collectors.toList());
            }

            if (peerScores.isEmpty()) return null;

            MultibaggerScore targetMbScore = allScores.stream()
                    .filter(s -> s.getSymbol().equals(symbol))
                    .findFirst().orElse(null);

            // Build target stock metrics
            PeerMetrics targetMetrics = buildPeerMetrics(symbol, targetMbScore);

            // Build peer metrics
            List<PeerMetrics> peerMetricsList = new ArrayList<>();
            for (MultibaggerScore peer : peerScores) {
                PeerMetrics pm = buildPeerMetrics(peer.getSymbol(), peer);
                peerMetricsList.add(pm);
            }

            // Sort by composite score descending
            peerMetricsList.sort(Comparator.comparingInt(PeerMetrics::getCompositeScore).reversed());

            // Calculate sector averages
            List<PeerMetrics> allMetrics = new ArrayList<>(peerMetricsList);
            if (targetMetrics != null) allMetrics.add(targetMetrics);

            double sectorAvgPE = allMetrics.stream()
                    .filter(m -> m.getStockPE() != null && m.getStockPE() > 0)
                    .mapToDouble(PeerMetrics::getStockPE)
                    .average().orElse(0);

            double sectorAvgGrowth = allMetrics.stream()
                    .filter(m -> m.getYoyRevenueGrowth() != null)
                    .mapToDouble(PeerMetrics::getYoyRevenueGrowth)
                    .average().orElse(0);

            // Calculate sector rank
            int sectorRank = 1;
            if (targetMetrics != null) {
                List<PeerMetrics> ranked = new ArrayList<>(allMetrics);
                ranked.sort(Comparator.comparingInt(PeerMetrics::getCompositeScore).reversed());
                for (int i = 0; i < ranked.size(); i++) {
                    if (ranked.get(i).getSymbol().equals(symbol)) {
                        sectorRank = i + 1;
                        break;
                    }
                }
            }

            // Build formatted comparison text
            String comparisonText = buildComparisonText(sector, targetMetrics, peerMetricsList,
                    sectorAvgPE, sectorAvgGrowth, sectorRank);

            // Assemble result
            PeerComparisonResult result = new PeerComparisonResult();
            result.setSymbol(symbol);
            result.setSector(sector);
            result.setPeers(peerMetricsList);
            result.setTargetStock(targetMetrics);
            result.setSectorRank(sectorRank);
            result.setSectorAvgPE(sectorAvgPE);
            result.setSectorAvgGrowth(sectorAvgGrowth);
            result.setComparisonText(comparisonText);

            return result;
        } catch (Exception e) {
            log.warn("compareWithMetrics failed for {} in {}: {}", symbol, sector, e.getMessage());
            return null;
        }
    }

    // ======================== Helper Methods ========================

    private PeerMetrics buildPeerMetrics(String symbol, MultibaggerScore mbScore) {
        PeerMetrics metrics = new PeerMetrics();
        metrics.setSymbol(symbol);

        // MultibaggerScore data
        if (mbScore != null) {
            metrics.setCompositeScore(mbScore.getCompositeScore());
            metrics.setGrade(mbScore.getGrade());
            metrics.setVerdict(mbScore.getVerdict());
            metrics.setMomentumScore(mbScore.getTechnicalMomentumScore());
            metrics.setRsScore(mbScore.getRelativeStrengthScore());
            metrics.setWeeklyRsi(mbScore.getWeeklyRsi());
        }

        // Valuation data
        ValuationData valuation = safeGetValuation(symbol);
        if (valuation != null) {
            metrics.setStockPE(valuation.getStockPe());
            metrics.setPriceToBook(valuation.getPriceToBook());
            metrics.setDividendYield(valuation.getDividendYield());
            metrics.setMarketCapCrores(valuation.getMarketCap());
        }

        // Earnings / growth data
        List<QuarterlyResult> quarterly = safeGetQuarterly(symbol);
        if (quarterly != null && !quarterly.isEmpty()) {
            metrics.setYoyRevenueGrowth(computeYoyRevenueGrowth(quarterly));
            metrics.setYoyProfitGrowth(computeYoyProfitGrowth(quarterly));
            metrics.setLatestNetMargin(computeNetMargin(quarterly));
        }

        return metrics;
    }

    private String buildComparisonText(String sector, PeerMetrics target, List<PeerMetrics> peers,
                                       double sectorAvgPE, double sectorAvgGrowth, int sectorRank) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- PEER COMPARISON WITH METRICS (").append(sector).append(") ---\n");
        sb.append(String.format("%-20s %-8s %-8s %-8s %-10s %-9s %-8s %-8s %-10s\n",
                "Stock", "Score", "Grade", "PE", "YoY Rev%", "Margin%", "RS", "RSI", "Verdict"));
        sb.append("-".repeat(100)).append("\n");

        if (target != null) {
            sb.append(formatMetricsRow(target, true));
        }
        for (PeerMetrics peer : peers) {
            sb.append(formatMetricsRow(peer, false));
        }

        sb.append(String.format("\nSector Rank: #%d out of %d stocks\n", sectorRank, peers.size() + (target != null ? 1 : 0)));
        sb.append(String.format("Sector Avg PE: %.1f", sectorAvgPE));
        if (target != null && target.getStockPE() != null && target.getStockPE() > 0) {
            String premium = target.getStockPE() > sectorAvgPE ? "PREMIUM TO SECTOR" : "DISCOUNT TO SECTOR";
            sb.append(String.format(" | This Stock PE: %.1f (%s)", target.getStockPE(), premium));
        }
        sb.append("\n");
        sb.append(String.format("Sector Avg YoY Revenue Growth: %+.1f%%\n", sectorAvgGrowth));

        // Best grower
        List<PeerMetrics> all = new ArrayList<>(peers);
        if (target != null) all.add(target);

        all.stream()
                .filter(m -> m.getYoyRevenueGrowth() != null)
                .max(Comparator.comparingDouble(PeerMetrics::getYoyRevenueGrowth))
                .ifPresent(best -> sb.append(String.format("Best Grower: %s (%+.1f%% YoY revenue)\n",
                        cleanSymbolName(best.getSymbol()), best.getYoyRevenueGrowth())));

        // Most undervalued
        all.stream()
                .filter(m -> m.getStockPE() != null && m.getStockPE() > 0
                        && m.getYoyRevenueGrowth() != null && m.getYoyRevenueGrowth() > 0)
                .min(Comparator.comparingDouble(PeerMetrics::getStockPE))
                .ifPresent(uv -> sb.append(String.format("Most Undervalued: %s (PE %.1f with %+.1f%% growth)\n",
                        cleanSymbolName(uv.getSymbol()), uv.getStockPE(), uv.getYoyRevenueGrowth())));

        // Highest margin
        all.stream()
                .filter(m -> m.getLatestNetMargin() != null)
                .max(Comparator.comparingDouble(PeerMetrics::getLatestNetMargin))
                .ifPresent(hm -> sb.append(String.format("Highest Margin: %s (%.1f%% net margin)\n",
                        cleanSymbolName(hm.getSymbol()), hm.getLatestNetMargin())));

        return sb.toString();
    }

    private String formatMetricsRow(PeerMetrics m, boolean isTarget) {
        return String.format("%-20s %-8d %-8s %-8s %-10s %-9s %-8d %-8s %-10s%s\n",
                truncate(m.getSymbol(), 20),
                m.getCompositeScore(),
                m.getGrade() != null ? m.getGrade() : "N/A",
                formatDouble(m.getStockPE(), "%.1f"),
                formatPercent(m.getYoyRevenueGrowth()),
                formatPercent(m.getLatestNetMargin()),
                m.getRsScore(),
                formatDouble(m.getWeeklyRsi(), "%.0f"),
                m.getVerdict() != null ? m.getVerdict() : "N/A",
                isTarget ? "  <-- THIS STOCK" : "");
    }

    private ValuationData safeGetValuation(String symbol) {
        try {
            return stockValuationService.getValuationData(symbol);
        } catch (Exception e) {
            log.debug("Could not fetch valuation for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private List<QuarterlyResult> safeGetQuarterly(String symbol) {
        try {
            String cleanSym = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            return nseDataService.fetchQuarterlyResults(cleanSym);
        } catch (Exception e) {
            log.debug("Could not fetch quarterly results for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Compute YoY revenue growth from quarterly results.
     * Compares latest quarter revenue with the quarter 4 periods ago (same quarter last year).
     * Falls back to comparing first and last available quarter if fewer than 4 quarters.
     */
    private Double computeYoyRevenueGrowth(List<QuarterlyResult> results) {
        if (results == null || results.size() < 2) return null;
        Double latest = results.get(0).getRevenue();
        Double yearAgo = results.size() >= 4 ? results.get(3).getRevenue() : results.get(results.size() - 1).getRevenue();
        if (latest == null || yearAgo == null || yearAgo == 0) return null;
        return ((latest - yearAgo) / Math.abs(yearAgo)) * 100.0;
    }

    /**
     * Compute YoY profit growth from quarterly results.
     */
    private Double computeYoyProfitGrowth(List<QuarterlyResult> results) {
        if (results == null || results.size() < 2) return null;
        Double latest = results.get(0).getProfit();
        Double yearAgo = results.size() >= 4 ? results.get(3).getProfit() : results.get(results.size() - 1).getProfit();
        if (latest == null || yearAgo == null || yearAgo == 0) return null;
        return ((latest - yearAgo) / Math.abs(yearAgo)) * 100.0;
    }

    /**
     * Compute net margin from the latest quarterly result (profit / revenue * 100).
     */
    private Double computeNetMargin(List<QuarterlyResult> results) {
        if (results == null || results.isEmpty()) return null;
        Double revenue = results.get(0).getRevenue();
        Double profit = results.get(0).getProfit();
        if (revenue == null || profit == null || revenue == 0) return null;
        return (profit / revenue) * 100.0;
    }

    private String formatDouble(Double value, String fmt) {
        return value != null ? String.format(fmt, value) : "N/A";
    }

    private String formatPercent(Double value) {
        return value != null ? String.format("%+.1f%%", value) : "N/A";
    }

    private String cleanSymbolName(String symbol) {
        return symbol != null && symbol.contains(":") ? symbol.split(":")[1] : symbol;
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen - 1) + "…" : s;
    }

    // ======================== DTOs ========================

    @Data
    public static class PeerComparisonResult {
        private String symbol;
        private String sector;
        private List<PeerMetrics> peers;  // sorted by composite score
        private PeerMetrics targetStock;
        private int sectorRank;
        private double sectorAvgPE;
        private double sectorAvgGrowth;
        private String comparisonText;  // formatted text for AI consumption
    }

    @Data
    public static class PeerMetrics {
        private String symbol;
        private int compositeScore;
        private String grade;
        private String verdict;
        // Valuation
        private Double stockPE;
        private Double priceToBook;
        private Double dividendYield;
        private Double marketCapCrores;
        // Growth
        private Double yoyRevenueGrowth;
        private Double yoyProfitGrowth;
        private Double latestNetMargin;
        // Technical
        private int momentumScore;
        private int rsScore;
        private Double weeklyRsi;
    }
}
