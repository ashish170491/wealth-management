package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One sub-score of a {@link RecommendationEntity}, persisted so per-dimension
 * Information Coefficient can later be computed for engines that have no
 * standalone score table of their own.
 *
 * <p>Why a sidecar table rather than columns on {@code recommendations}: the
 * three scoring engines have <em>different</em> dimension sets (Multibagger 8,
 * Quant Discovery 5, Sector Reversal 4), so a wide table would be mostly null.
 * A narrow (recommendationId, dimension, score) row is engine-agnostic.
 *
 * <p>Note: MULTIBAGGER and SECTOR_REVERSAL already persist their sub-scores on
 * their own score tables ({@code multibagger_scores}, {@code
 * sector_reversal_signals}) and the IC path reads those directly. In practice
 * this sidecar is populated for QUANT_DISCOVERY, which has no such table.
 *
 * See SPEC.md §23.2 (per-dimension IC) and §25.1.
 */
@Entity
@Table(name = "recommendation_dimensions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reco_dim",
                columnNames = {"recommendationId", "dimension"}),
        indexes = @Index(name = "idx_reco_dim_recid", columnList = "recommendationId"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationDimensionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recommendationId;

    /** Human-readable dimension label, e.g. "Earnings Growth". */
    @Column(nullable = false, length = 48)
    private String dimension;

    /** Raw sub-score as produced by the engine (each engine uses its own scale). */
    @Column(nullable = false)
    private int score;
}
