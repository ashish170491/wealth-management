package com.example.trading.portfolio.risk;

import com.example.trading.portfolio.AllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Diversification-risk metrics (SPEC.md §7). Reuses {@link AllocationService} for actual weights.
 *
 * <p>Correlation-cluster detection (listed in SPEC §7 as a required metric) is not yet wired —
 * it requires pulling 90-day daily-return series for each holding; MVP ships without it and
 * returns an empty cluster list, keeping the response shape stable for future addition.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiversificationService {

    private final AllocationService allocationService;
    private final DiversificationConfig config;
    private final com.example.trading.persistence.HoldingsHistoryRepository historyRepository;

    public DiversificationDto.RiskResponse computeRisk() {
        AllocationService.ActualWeights actual = allocationService.computeActualWeights();
        List<DiversificationDto.Alert> alerts = new ArrayList<>();

        double hhi = computeHhi(actual.byStock());
        String hhiClass = classifyHhi(hhi);
        if ("DANGEROUS".equals(hhiClass)) {
            alerts.add(new DiversificationDto.Alert("RED", "HHI",
                    String.format("Portfolio HHI %.0f exceeds dangerous threshold %.0f — heavy concentration",
                            hhi, config.getHhiDangerousThreshold())));
        } else if ("CONCENTRATED".equals(hhiClass)) {
            alerts.add(new DiversificationDto.Alert("YELLOW", "HHI",
                    String.format("Portfolio HHI %.0f above moderate threshold %.0f",
                            hhi, config.getHhiConcentratedThreshold())));
        }

        DiversificationDto.ConcentrationCheck sector = buildConcentration(
                actual.bySector(), config.getSectorMaxPercent());
        if (sector.exceeds()) {
            alerts.add(new DiversificationDto.Alert("RED", "SECTOR",
                    String.format("Sector '%s' at %.1f%% exceeds limit %.0f%%",
                            sector.topBucket(), sector.topWeight(), config.getSectorMaxPercent())));
        }

        DiversificationDto.ConcentrationCheck stock = buildConcentration(
                actual.byStock(), config.getStockMaxPercent());
        if (stock.exceeds()) {
            alerts.add(new DiversificationDto.Alert("RED", "STOCK",
                    String.format("Stock '%s' at %.1f%% exceeds single-stock limit %.0f%%",
                            stock.topBucket(), stock.topWeight(), config.getStockMaxPercent())));
        }

        CorrelationClusters.Result correlation = correlation(actual.byStock());
        if (correlation != null) {
            for (CorrelationClusters.Cluster c : correlation.clusters()) {
                if (c.weightPercent() <= config.getCorrelationClusterMaxPercent()) continue;
                alerts.add(new DiversificationDto.Alert("YELLOW", "CORRELATION", String.format(
                        "%s move together (average correlation %.2f over %d days) and are %.1f%% of the "
                                + "portfolio combined - more than the %.0f%% limit. Owning them is closer to one "
                                + "larger position than to %d separate ones.",
                        String.join(", ", c.members()), c.averageCorrelation(), config.getCorrelationLookbackDays(),
                        c.weightPercent(), config.getCorrelationClusterMaxPercent(), c.members().size())));
            }
        }

        return new DiversificationDto.RiskResponse(
                actual.totalValue(),
                actual.holdingsCount(),
                new DiversificationDto.HhiMetrics(hhi, hhiClass),
                sector,
                stock,
                actual.byCap(),
                alerts,
                correlation);
    }

    /**
     * Correlation clusters from the daily closes already stored in {@code holdings_history}
     * (SPEC 7.2). DB-only - one query - so the Overview can keep calling this on page load.
     * Symbols are keyed by their exchange-free base, because the same holding is written under
     * NSE and BSE prefixes on different days (B-061). Null on failure, never "no clusters".
     */
    private CorrelationClusters.Result correlation(java.util.Map<String, Double> byStock) {
        try {
            java.util.Map<String, Double> weights = new java.util.HashMap<>();
            for (var e : byStock.entrySet()) {
                weights.merge(com.example.trading.holdings.SymbolVariants.base(e.getKey()), e.getValue(), Double::sum);
            }
            java.time.LocalDate from = java.time.LocalDate.now().minusDays(config.getCorrelationLookbackDays());
            java.util.Map<String, java.util.NavigableMap<java.time.LocalDate, Double>> closes = new java.util.HashMap<>();
            for (var h : historyRepository.findByRecordDateGreaterThanEqualOrderByRecordDateAsc(from)) {
                String base = com.example.trading.holdings.SymbolVariants.base(h.getSymbol());
                if (!weights.containsKey(base) || h.getClosePrice() <= 0) continue;
                closes.computeIfAbsent(base, k -> new java.util.TreeMap<>()).put(h.getRecordDate(), h.getClosePrice());
            }
            return CorrelationClusters.compute(closes, weights, config.getCorrelationClusterThreshold());
        } catch (Exception e) {
            log.warn("Correlation clusters unavailable ({}) - the risk view will say so rather than "
                    + "reporting that nothing moves together.", e.getMessage());
            return null;
        }
    }

    private double computeHhi(Map<String, Double> stockWeights) {
        // Weights are percentages (0–100). HHI on percentages sums to up to 10,000.
        return stockWeights.values().stream()
                .mapToDouble(w -> w * w)
                .sum();
    }

    private String classifyHhi(double hhi) {
        if (hhi >= config.getHhiDangerousThreshold()) return "DANGEROUS";
        if (hhi >= config.getHhiConcentratedThreshold()) return "CONCENTRATED";
        if (hhi >= 1500) return "MODERATE";
        return "LOW";
    }

    private DiversificationDto.ConcentrationCheck buildConcentration(
            Map<String, Double> weights, double thresholdPercent) {
        List<DiversificationDto.Bucket> sorted = weights.entrySet().stream()
                .map(e -> new DiversificationDto.Bucket(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(DiversificationDto.Bucket::weight).reversed())
                .toList();
        if (sorted.isEmpty()) {
            return new DiversificationDto.ConcentrationCheck(null, 0.0, thresholdPercent, false, sorted);
        }
        DiversificationDto.Bucket top = sorted.get(0);
        return new DiversificationDto.ConcentrationCheck(
                top.key(), top.weight(), thresholdPercent,
                top.weight() > thresholdPercent, sorted);
    }
}
