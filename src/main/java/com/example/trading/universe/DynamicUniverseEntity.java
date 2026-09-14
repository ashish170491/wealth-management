package com.example.trading.universe;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A stock the system discovered for itself, outside the curated tier lists (SPEC §30).
 *
 * <p>Rows are <b>deactivated, never deleted</b>. A symbol that was promoted and later
 * dropped out is evidence about how the funnel behaves, and deleting it would make the
 * expansion look better than it was — the same survivorship problem SPEC §25.1 warns about
 * for recommendations.
 */
@Entity
@Table(name = "dynamic_universe",
        uniqueConstraints = @UniqueConstraint(name = "uk_dynamic_universe_symbol", columnNames = "symbol"),
        indexes = @Index(name = "idx_dynamic_universe_active", columnList = "active"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicUniverseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(length = 128)
    private String companyName;

    /** COARSE_SCAN / IPO_TRACKER / MANUAL. */
    @Column(length = 24)
    private String sourceReason;

    /** QUEUED → PROMOTED → RETIRED. Queued rows are awaiting deep fundamental scoring. */
    @Column(length = 16)
    private String status;

    private LocalDate discoveredDate;
    private LocalDate promotedDate;
    private LocalDate retiredDate;

    /** Listing date from NSE's equity list — drives the IPO tracker. Nullable. */
    private LocalDate listingDate;

    /** Coarse Stage-A score (0-100) that got it into the queue. */
    private Integer coarseScore;

    private Integer lastCompositeScore;
    private LocalDate lastScreenedDate;

    /**
     * Consecutive weekly screenings below the retirement floor. Reset to 0 by any run at or
     * above it — a stock has to be persistently weak to be dropped, not merely weak once.
     */
    @Builder.Default
    private Integer consecutiveWeakRuns = 0;

    /** 20-day average traded value at discovery, rupees. The buyability gate (SPEC §12.9). */
    private Double liquidityAdv20d;

    @Builder.Default
    private boolean active = true;

    @Column(length = 256)
    private String note;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
