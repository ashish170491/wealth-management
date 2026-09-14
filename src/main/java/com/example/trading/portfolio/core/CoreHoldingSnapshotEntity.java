package com.example.trading.portfolio.core;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One holding's tier on one day (SPEC §35.6).
 *
 * <p>Written every weekday whether or not anything changed, because the point of the table is to
 * let the classifier's <em>own</em> accuracy be judged later: a tier history that only records
 * changes cannot answer "what did it say about this stock last March".
 *
 * <p>{@code tier} is that day's raw reading; {@code effectiveTier} is what survived hysteresis and
 * is the only one the behavioural overlay consults. {@code observedAlerts} is the observation-mode
 * evidence — the technical exit alerts that fired on this holding while it was core, which is what
 * the flag flip will eventually be argued from.
 *
 * <p>Every numeric column is a wrapper type. Adding a primitive {@code int} to a populated table
 * fails Hibernate's {@code ddl-auto=update} (it adds the column {@code NOT NULL}), and relaxing it
 * afterwards needs an explicit migration because update never drops a constraint (B-026).
 */
@Entity
@Table(name = "holding_classification",
        uniqueConstraints = @UniqueConstraint(name = "uk_holding_classification_symbol_date",
                columnNames = {"symbol", "classified_on"}),
        indexes = {
                @Index(name = "idx_holding_classification_date", columnList = "classified_on"),
                @Index(name = "idx_holding_classification_symbol", columnList = "symbol")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoreHoldingSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(length = 32)
    private String tradingSymbol;

    @Column(length = 32)
    private String isin;

    @Column(name = "classified_on", nullable = false)
    private LocalDate classifiedOn;

    /** That day's raw reading, before hysteresis. */
    @Column(length = 16)
    private String tier;

    /** What the overlay acts on. Differs from {@link #tier} while a change is accumulating anchors. */
    @Column(length = 16)
    private String effectiveTier;

    /** Null when fewer than {@code durability-min-components} were measured — never a filler number. */
    private Integer durabilityScore;

    @Column(columnDefinition = "TEXT")
    private String durabilityCoverage;

    /** {@code G1:PASS;G2:FAIL;...} — the per-gate record, including PASS_NO_DATA. */
    @Column(columnDefinition = "TEXT")
    private String gatesJson;

    @Column(columnDefinition = "TEXT")
    private String softSignals;

    @Column(columnDefinition = "TEXT")
    private String missingInputs;

    @Column(columnDefinition = "TEXT")
    private String reasons;

    /** Observation-mode evidence: technical alerts that fired on this holding today. */
    @Column(columnDefinition = "TEXT")
    private String observedAlerts;

    /** Non-null while a tier change is waiting on weekly anchors. */
    @Column(columnDefinition = "TEXT")
    private String pendingChange;

    /** FORCE_CORE / FORCE_SATELLITE when the investor's own judgement overrode the gates. */
    @Column(length = 32)
    private String overrideApplied;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
