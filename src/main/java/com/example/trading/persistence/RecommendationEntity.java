package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A stock recommendation issued by one of the system's scoring engines
 * (multibagger screener, quantitative discovery, sector reversal). Captured
 * the moment the score crosses the engine's recommendation threshold so the
 * pick can later be evaluated against realized market returns.
 *
 * One row per (symbol, source, issuedDate) — re-runs on the same day upsert.
 * See SPEC.md §23 Recommendation Accuracy Tracking.
 */
@Entity
@Table(name = "recommendations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reco_symbol_source_date",
                columnNames = {"symbol", "source", "issuedDate"}),
        indexes = {
                @Index(name = "idx_reco_issued", columnList = "issuedDate"),
                @Index(name = "idx_reco_source", columnList = "source"),
                @Index(name = "idx_reco_symbol", columnList = "symbol")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false)
    private LocalDate issuedDate;

    @Column(nullable = false)
    private double issuedPrice;

    private Double niftyIndexAtIssue;

    private Integer score;
    @Column(length = 8)
    private String grade;
    @Column(length = 32)
    private String verdict;

    private Double targetPrice;
    private Double stopLossPrice;

    @Column(length = 64)
    private String sector;

    @Column(length = 32)
    private String marketCapCategory;

    /**
     * Version of the scoring configuration that issued this pick (SPEC §38.1).
     *
     * <p>An outcome measured a year from now says nothing useful unless the engine that
     * produced the pick can be identified — the weights, the shadow-mode flags and the
     * scoring logic all move. Nullable: picks recorded before 2026-08-29 have no stamp and
     * must be treated as unknown provenance rather than assumed current.
     */
    @Column(length = 32)
    private String scoringVersion;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (issuedDate == null) issuedDate = LocalDate.now();
    }

    public enum Source {
        MULTIBAGGER,
        QUANT_DISCOVERY,
        SECTOR_REVERSAL,
        /**
         * Macro event exposure (SPEC 48). <b>These rows are measurements, not picks.</b>
         *
         * <p>A MACRO_EVENT row records that the app read a stock as facing a headwind or a tailwind
         * on a given day, so that reading can be checked against what the price actually did at 30,
         * 90, 180 and 365 days. It is not a claim that the stock is worth owning: the feature adds
         * zero points to any score and its vocabulary contains no instruction to transact. Anything
         * presenting "the app's picks" to the investor must filter this source out, or a risk note
         * will be read as a recommendation - see {@code RecommendationAccuracyController}.
         */
        MACRO_EVENT
    }
}
