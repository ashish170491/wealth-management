package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What an alternative dimension weighting <i>would</i> have scored a stock on a screening run
 * (SPEC §38.7, step 1). One row per (screeningDate, symbol, variant).
 *
 * <p><b>This table steers nothing.</b> No report reads it, no recommendation is issued from it,
 * no threshold consults it. It exists so that when the question "should the weights change?"
 * is finally answerable — which needs 180-day outcomes, first maturing around October 2026 —
 * there is a record of what the alternatives claimed, written down before the answer was known.
 * A weighting compared against returns after seeing them is not evidence, and that is the exact
 * failure that ended the previous machine-learning chain (SPEC §25.5).
 *
 * <p><b>Why the composite is reconstructed rather than re-screened.</b> A variant differs from
 * the live vector only in how the seven dimension sub-scores are combined; every bonus, the
 * market-cap adjustment and the HIGH_RISK cap are computed from data that does not depend on
 * the weights at all. So {@code variantComposite = clamp(variantWeightedBase + adjustment)},
 * where {@code adjustment} is everything the engine did after weighting, recovered by
 * subtraction from the stored composite. This makes the live variant reproduce the stored
 * composite exactly, makes every alternative differ from it by precisely the reweighting, and —
 * the reason it was chosen — lets the whole history since April 2026 be back-filled instead of
 * starting the evidence clock today.
 *
 * <p><b>{@link #reconstructionExact} is the honesty column.</b> The bonus chain clamps to
 * 0..100 at each step and the cap is a hard floor-to-54, so on rows sitting at a boundary the
 * adjustment is not a clean additive constant and a variant's reconstruction may be off. Those
 * rows say so rather than being quietly averaged in — the same rule as a null sub-score being
 * dropped rather than replaced with a neutral 50.
 */
@Entity
@Table(name = "shadow_composites",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_shadow_date_symbol_variant",
                columnNames = {"screeningDate", "symbol", "variantName"}),
        indexes = {
                @Index(name = "idx_shadow_date", columnList = "screeningDate"),
                @Index(name = "idx_shadow_variant", columnList = "variantName"),
                @Index(name = "idx_shadow_date_variant", columnList = "screeningDate,variantName")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowCompositeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Same key {@code multibagger_scores} uses, so the two join without translation. */
    @Column(nullable = false)
    private LocalDate screeningDate;

    /** Exchange-qualified, e.g. {@code NSE:RELIANCE}. */
    @Column(nullable = false, length = 64)
    private String symbol;

    /** Variant identity from {@code WeightVariantRegistry}; {@code live} is the incumbent. */
    @Column(nullable = false, length = 48)
    private String variantName;

    /**
     * Revision of the candidate set this row was produced under
     * ({@code WeightVariantRegistry.VARIANT_SET_REVISION}). Two rows named {@code quality-tilt}
     * under different revisions may be different vectors, and without this there is no way to
     * find out afterwards — the same lesson as scoring provenance (SPEC §38.1).
     */
    private Integer variantSetRevision;

    /**
     * Scoring version of the underlying {@code multibagger_scores} row (SPEC §38.1). Copied,
     * not recomputed: it describes the run that produced the sub-scores, which is what makes
     * two rows comparable. Null means the source row predates versioning — unknown provenance,
     * never "probably current".
     */
    @Column(length = 32)
    private String scoringVersion;

    /** This variant's composite, 0-100, after the run's own bonuses and caps were re-applied. */
    @Column(nullable = false)
    private Integer composite;

    /** Weighted, renormalised score over the measured dimensions, before bonuses. */
    private Integer weightedBase;

    /**
     * Everything the live engine did after weighting — market-cap adjustment, every bonus, and
     * any hard cap — recovered as {@code storedComposite − liveWeightedBase} and re-applied
     * identically to every variant. Held constant across variants on purpose: none of it
     * depends on the weights, so varying it would be measuring something other than the
     * weighting.
     */
    private Integer adjustment;

    /** Cross-sectional rank within this variant on this date; 100 = best. Null when alone. */
    private Double percentileRank;

    /** Dimensions that carried weight under this variant and were actually measured. */
    private Integer dimensionsMeasured;

    /**
     * False when the source row sits at a clamp boundary (composite 0 or 100) or carried the
     * HIGH_RISK / auditor cap, so the additive reconstruction above is not exact for this row.
     * Analysis excludes these rather than averaging them in. Never null: it is a property of
     * the source row, always determinable.
     */
    @Column(nullable = false)
    private Boolean reconstructionExact;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (screeningDate == null) {
            screeningDate = LocalDate.now();
        }
    }
}
