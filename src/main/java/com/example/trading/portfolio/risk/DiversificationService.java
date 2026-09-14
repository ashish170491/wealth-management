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

        return new DiversificationDto.RiskResponse(
                actual.totalValue(),
                actual.holdingsCount(),
                new DiversificationDto.HhiMetrics(hhi, hhiClass),
                sector,
                stock,
                actual.byCap(),
                alerts);
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
