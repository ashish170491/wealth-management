package com.example.trading.portfolio;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A named portfolio profile — a set of target allocation weights plus drift tolerances.
 * Only one profile is {@code active} at a time. See SPEC.md §5.
 */
@Entity
@Table(name = "portfolio_profile")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean active;

    /** Absolute tolerance in percentage points; null falls back to {@link PortfolioConfig}. */
    private Double toleranceAbsolutePp;

    /** Relative tolerance (0.25 = 25%); null falls back to {@link PortfolioConfig}. */
    private Double toleranceRelative;

    /**
     * True once the investor has actually set the target weights themselves.
     *
     * <p>A default is not a statement (Gotcha 68) - the same rule that produced
     * {@code holding_conviction.thesis_stated} (B-097) and {@code horizon_stated} (B-057). Without
     * this flag the seeded profile's targets are indistinguishable from chosen ones, and on
     * 2026-09-18 that put <b>ten</b> permanent drift warnings on the landing page - half the whole
     * attention list - against targets nobody picked: 20% IT against 0.43% held, 25% Banking
     * against 3.77%. Ten alerts that fire every day and can never clear is the section-nobody-reads
     * failure (Gotcha 132) landing on the app's main action surface.
     *
     * <p>Set by {@code AllocationService.replaceTargetWeights}, i.e. the investor's own write
     * through {@code PUT /api/portfolio/profile}, and never by the seeder.
     *
     * <p><b>Null means unknown, not false.</b> A profile written before this column existed cannot
     * say who chose its targets, so it is neither trusted nor discarded: the drift table still
     * renders in full, and the landing page raises one row asking for confirmation instead of ten
     * claiming a finding.
     */
    private Boolean targetsStated;

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
