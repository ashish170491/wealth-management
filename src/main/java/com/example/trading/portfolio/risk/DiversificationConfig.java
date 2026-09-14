package com.example.trading.portfolio.risk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Diversification-risk thresholds. See SPEC.md §7.
 */
@Component
@ConfigurationProperties(prefix = "portfolio.risk")
@Data
public class DiversificationConfig {
    /** HHI threshold above which portfolio is flagged "concentrated". */
    private double hhiConcentratedThreshold = 2500.0;

    /** HHI threshold above which portfolio is flagged "dangerous". */
    private double hhiDangerousThreshold = 4000.0;

    /** Any single sector above this percent raises a concentration alert. */
    private double sectorMaxPercent = 30.0;

    /** Any single stock above this percent raises a concentration alert. */
    private double stockMaxPercent = 15.0;

    /** Correlation clusters: pairwise threshold above which two stocks are "linked". */
    private double correlationClusterThreshold = 0.7;

    /** Cluster combined-weight alert threshold. */
    private double correlationClusterMaxPercent = 20.0;

    /** Days of daily-return history used for correlation computation. */
    private int correlationLookbackDays = 90;
}
