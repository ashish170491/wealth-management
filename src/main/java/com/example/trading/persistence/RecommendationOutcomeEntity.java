package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Realized return of a single {@link RecommendationEntity} measured at a fixed
 * horizon (e.g., 30 / 90 / 180 / 365 days after the issue date). One row per
 * (recommendationId, horizonDays). Populated by
 * {@code RecommendationOutcomeScheduler} when a pick hits its anniversary.
 *
 * See SPEC.md §23 Recommendation Accuracy Tracking.
 */
@Entity
@Table(name = "recommendation_outcomes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reco_outcome_reco_horizon",
                columnNames = {"recommendationId", "horizonDays"}),
        indexes = {
                @Index(name = "idx_reco_outcome_reco", columnList = "recommendationId"),
                @Index(name = "idx_reco_outcome_measured", columnList = "measuredDate")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationOutcomeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recommendationId;

    @Column(nullable = false)
    private int horizonDays;

    @Column(nullable = false)
    private LocalDate measuredDate;

    @Column(nullable = false)
    private double priceAtMeasurement;

    @Column(nullable = false)
    private double returnPercent;

    private Double niftyReturnPercent;
    private Double excessReturnPercent;

    private Boolean targetHit;
    private Boolean stopLossHit;

    /**
     * Actual days between the pick's issue date and {@link #measuredDate} (B-028).
     *
     * <p>{@link #horizonDays} is the horizon this row is *labelled* with; this is how long the
     * return really ran for. They diverge whenever a pick is measured late — the scheduler
     * selects every unmeasured pick <i>older than</i> the cutoff, not one sitting exactly on its
     * anniversary, so a pick issued 200 days ago was being stored as a "30-day" outcome computed
     * from today's price. 20.8% of 30d rows and 29.4% of 90d rows were mislabelled this way,
     * which silently inflated the horizons behind every hit rate and Information Coefficient.
     *
     * <p>Nullable only for rows written before this column existed; {@code SchemaMigrationRunner}
     * backfills them from {@code measured_date - issued_date}.
     */
    private Integer daysElapsed;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public static final int[] DEFAULT_HORIZONS = {30, 90, 180, 365};
}
