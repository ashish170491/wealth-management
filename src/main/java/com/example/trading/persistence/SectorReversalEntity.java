package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity to track sector reversal signals and stock recommendations.
 */
@Entity
@Table(name = "sector_reversal_signals",
    indexes = {
        @Index(name = "idx_sector_date", columnList = "sectorName, scanDate"),
        @Index(name = "idx_symbol_date", columnList = "symbol, scanDate"),
        @Index(name = "idx_upside_score", columnList = "upsideScore DESC")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SectorReversalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Sector info
    private String sectorName;
    private LocalDate scanDate;
    private String sectorTrend;              // BULLISH, BEARISH, SIDEWAYS
    private boolean sectorReversing;
    private String reversalType;             // BULLISH_CROSSOVER, BEARISH_CROSSOVER
    private Double reversalStrength;         // -100 to 100

    // Stock info
    private String symbol;
    private Double upsideScore;              // 0-100 overall score
    private String recommendation;           // STRONG_BUY, BUY, ACCUMULATE, WATCH

    // Price levels
    private Double currentPrice;
    private Double suggestedEntry;
    private Double stopLoss;
    private Double target1;
    private Double target2;
    private Double riskReward;

    // Component scores
    private Double macdScore;
    private Double rsiScore;
    private Double volumeScore;
    private Double priceScore;

    // Key indicators
    private Double rsi;
    private Double volumeRatio;

    // Summary
    @Column(length = 1000)
    private String signals;                  // Comma-separated signal descriptions

    @Column(length = 500)
    private String upsideReason;

    // Tracking
    private boolean alerted;                 // Whether alert was sent
    private boolean acted;                   // Whether trade was taken

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
