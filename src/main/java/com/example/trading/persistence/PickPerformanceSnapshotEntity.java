package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Daily snapshot of sector reversal pick performance across 7/15/30-day periods.
 * Persisted each time the performance report runs, enabling historical trend analysis.
 */
@Entity
@Table(name = "pick_performance_snapshots", indexes = {
    @Index(name = "idx_pick_perf_symbol", columnList = "symbol"),
    @Index(name = "idx_pick_perf_snapshot_date", columnList = "snapshotDate"),
    @Index(name = "idx_pick_perf_symbol_snapshot", columnList = "symbol, snapshotDate"),
    @Index(name = "idx_pick_perf_value_score", columnList = "valuePickScore DESC")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PickPerformanceSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Stock identification
    @Column(nullable = false)
    private String symbol;

    private String sector;

    // Original signal info (from earliest pick in 30-day window)
    private String recommendation;
    private LocalDate firstSignalDate;
    private Double signalPrice;

    // Current market data
    private Double currentPrice;
    private LocalDate snapshotDate;

    // Multi-period returns (null if no pick exists in that period)
    private Double return7d;
    private Double return15d;
    private Double return30d;

    // Target/SL tracking
    private Double target1;
    private Double stopLoss;
    private String status;              // WINNER, LOSER, TARGET_HIT, SL_HIT

    // Pick frequency
    private Integer timesPicked;        // How many times recommended in 30 days
    private Integer uniqueScanDates;    // Distinct days it appeared

    // === VALUE INVESTING SCORES (0-25 each, total 0-100) ===

    /** How often the stock gets recommended (more picks = higher conviction) */
    private Double pickFrequencyScore;

    /** Positive across all available periods = highest score */
    private Double returnConsistencyScore;

    /** Low max drawdown from signal price = resilient stock */
    private Double drawdownResilienceScore;

    /** Return trend improving over time (7d better than 30d) */
    private Double momentumTrendScore;

    /** Combined value investing score (0-100) */
    private Double valuePickScore;

    /** Classification based on multi-period behavior */
    @Column(length = 50)
    private String pickCategory;        // STEADY_CLIMBER, TURNAROUND, CONVICTION_PICK, FALLING_KNIFE, etc.

    // Original technical score from sector reversal scan
    private Double upsideScore;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
