package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * How much of the universe each scoring signal was actually measured on, per screening run
 * (SPEC §38.2). One row per (screeningDate, signal).
 *
 * <p>This is the record that separates "this signal does not predict returns" from "this
 * signal was never computed for most stocks". Without it both read as an Information
 * Coefficient near zero, and the system has twice spent months treating the second as the
 * first (bug #9 Institutional Interest, B-060 monthly RSI).
 *
 * <p>The run-level counts are repeated on every row of a run deliberately: a row is then
 * self-describing, and the fate of every universe symbol is visible without a second query.
 * The three ways a symbol fails to reach the table — rejected by the tier gate, failed to
 * score, or scored and retained — are recorded separately, because only one of them is a
 * blind spot (Gotcha 34).
 */
@Entity
@Table(name = "screening_coverage",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_coverage_date_signal",
                columnNames = {"screeningDate", "signalName"}),
        indexes = {
                @Index(name = "idx_coverage_date", columnList = "screeningDate"),
                @Index(name = "idx_coverage_signal", columnList = "signalName")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreeningCoverageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate screeningDate;

    /** Signal name as defined in {@code ScreeningCoverage.SPECS}, e.g. {@code Valuation}. */
    @Column(nullable = false, length = 64)
    private String signalName;

    /** Scoring version that produced the run (SPEC §38.1). */
    @Column(length = 32)
    private String scoringVersion;

    /** Symbols the run set out to screen. */
    private Integer universeSize;

    /** Symbols that produced a retained score row. */
    private Integer screenedCount;

    /**
     * Symbols scored and then dropped by the tier quality gate. Kept separate from
     * {@link #failedCount} on purpose: a stock rejected for being a penny stock is a decision,
     * a stock that could not be scored at all is a blind spot, and the whole point of this
     * table is refusing to conflate the two. {@code screenedCount + qualityRejectedCount +
     * failedCount} should equal {@code universeSize}; a shortfall is unexplained loss.
     */
    private Integer qualityRejectedCount;

    /** Symbols that threw or produced no score — genuine blind spots (Gotcha 34). */
    private Integer failedCount;

    /** Screened stocks this signal was evaluated against (equals {@code screenedCount}). */
    private Integer attempted;

    private Integer measured;

    /** Stocks where the signal legitimately does not apply — banks for ROCE, and so on. */
    private Integer notApplicable;

    /** Genuine gaps. */
    private Integer notMeasured;

    /** measured / (attempted − notApplicable). Null when every stock was not-applicable. */
    private Double coveragePercent;

    /** Cross-sectional mean of measured values; null for verdict signals and small samples. */
    private Double meanValue;

    /** Cross-sectional spread; null below the minimum sample, never 0-as-unknown. */
    private Double stdDev;

    /**
     * True when the spread is below the healthy threshold — the signal is not separating the
     * universe. Null (not false) when spread could not be computed.
     */
    private Boolean collapsed;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (screeningDate == null) {
            screeningDate = LocalDate.now();
        }
    }
}
