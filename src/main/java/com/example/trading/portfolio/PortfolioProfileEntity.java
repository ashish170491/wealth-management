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
