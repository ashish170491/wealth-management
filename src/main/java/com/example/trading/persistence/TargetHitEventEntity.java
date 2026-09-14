package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Records that a stock reached its target price, so the daily target-hit email
 * (SPEC.md §26) alerts on each target only once. Without persisted dedup the
 * same hit would re-email every day — and the app restarts daily, so an
 * in-memory set wouldn't survive.
 *
 * <p>The {@code dedupKey} = {@code source|symbol|targetPrice} (target rounded to
 * 2 dp). A genuinely new, higher target for the same symbol therefore produces a
 * new key and re-alerts, while a re-issued identical target stays suppressed.
 */
@Entity
@Table(name = "target_hit_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_target_hit_dedup", columnNames = "dedupKey"),
        indexes = @Index(name = "idx_target_hit_date", columnList = "hitDate"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TargetHitEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** RECOMMENDATION engine name (QUANT_DISCOVERY / SECTOR_REVERSAL) or HOLDING. */
    @Column(nullable = false, length = 32)
    private String source;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false)
    private double targetPrice;

    /** Price observed when the hit was detected. */
    @Column(nullable = false)
    private double hitPrice;

    @Column(nullable = false)
    private LocalDate hitDate;

    /** Unique idempotency key: {@code source|symbol|targetPrice}. */
    @Column(nullable = false, length = 96)
    private String dedupKey;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (hitDate == null) hitDate = LocalDate.now();
    }
}
