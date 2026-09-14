package com.example.trading.portfolio.risk;

import java.util.List;
import java.util.Map;

/** API DTOs for Diversification Risk Metrics (SPEC.md §7). */
public final class DiversificationDto {

    private DiversificationDto() {}

    public record RiskResponse(
            double totalPortfolioValue,
            int holdingsCount,
            HhiMetrics hhi,
            ConcentrationCheck sectorConcentration,
            ConcentrationCheck stockConcentration,
            Map<String, Double> marketCapMix,
            List<Alert> alerts
    ) {}

    /** Herfindahl-Hirschman Index (sum of squared percentage weights). */
    public record HhiMetrics(
            double value,
            String classification // LOW | MODERATE | CONCENTRATED | DANGEROUS
    ) {}

    /** Worst-offender view of a concentration dimension. */
    public record ConcentrationCheck(
            String topBucket,
            double topWeight,
            double thresholdPercent,
            boolean exceeds,
            List<Bucket> all
    ) {}

    public record Bucket(String key, double weight) {}

    public record Alert(String severity, String category, String message) {}
}
