package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One recorded verdict of the weight-promotion gate (SPEC §38.7, step 3).
 *
 * <p><b>Why the reviews are persisted rather than recomputed.</b> The gate requires the same
 * challenger to lead on two consecutive reviews, and that condition is unenforceable without a
 * memory: recomputing "what did the last review say" from today's data would simply produce
 * today's answer twice and call it agreement. The deleted machine-learning chain had exactly
 * this hole — nothing recorded what any earlier evaluation had concluded, so after the fact
 * there was no way to tell a stable result from a lucky one (SPEC §38.1).
 *
 * <p>A row here is written every time the review runs, whatever it concludes. Refusals are the
 * expected content for a long time, and they are the record that the question was asked and
 * honestly answered — which is the part that is missing when someone later argues the weights
 * "have never been validated".
 */
@Entity
@Table(name = "weight_reviews",
        indexes = {
                @Index(name = "idx_weight_review_date", columnList = "reviewDate"),
                @Index(name = "idx_weight_review_variant", columnList = "variantName")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeightReviewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate reviewDate;

    /** Forward window the evaluation used, in days. Reviews at different horizons are separate. */
    private Integer horizonDays;

    /** The leading challenger, or null when none was measurable. */
    @Column(length = 48)
    private String variantName;

    /** Candidate-set revision, so a later reader knows which vector that name meant. */
    private Integer variantSetRevision;

    /** How many alternatives were in the set — the deflation factor applied to significance. */
    private Integer variantsTried;

    /** Non-overlapping periods behind the verdict. The honest sample size (SPEC §25.5). */
    private Integer independentPeriods;

    /** Mean paired rank-correlation advantage over the live vector. */
    private Double meanIcVsLive;

    /**
     * Paired t-statistic against the live vector, and the deflated value it had to clear.
     *
     * <p>Named {@code pairedTStatistic} rather than {@code tStatistic} for a mundane but
     * load-bearing reason: Lombok generates {@code getTStatistic()} for a field beginning with a
     * single lower-case letter followed by a capital, and Jackson then serialises it as
     * {@code tstatistic} while every neighbouring field stays camelCase. A consumer reading
     * {@code tStatistic} would silently get nothing. Found on the first live response.
     */
    private Double pairedTStatistic;

    private Double requiredT;

    /**
     * Every condition except stability was met. This, not {@link #eligible}, is what the next
     * review reads: the first qualifying review necessarily fails stability for want of a
     * predecessor, so scoring it as a failure would reset the run forever.
     */
    private Boolean passedExceptStability;

    /** Every condition including stability was met. Even then, adoption stays manual. */
    private Boolean eligible;

    /** Whether the weights were actually changed as a result. Always false so far, by design. */
    private Boolean adopted;

    /** The gate's own summary line, stored verbatim so the reasoning survives with the verdict. */
    @Column(length = 2000)
    private String summary;

    /** Every condition checked, passed and failed, newline-separated. */
    @Column(length = 4000)
    private String reasons;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (reviewDate == null) {
            reviewDate = LocalDate.now();
        }
        if (adopted == null) {
            adopted = false;
        }
    }
}
