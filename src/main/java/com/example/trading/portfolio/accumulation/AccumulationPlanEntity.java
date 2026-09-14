package com.example.trading.portfolio.accumulation;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A multi-tranche plan to accumulate a target ₹ amount of a single symbol over time.
 * See SPEC.md §8.
 */
@Entity
@Table(name = "accumulation_plan", indexes = {
        @Index(name = "idx_acc_plan_symbol", columnList = "symbol"),
        @Index(name = "idx_acc_plan_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccumulationPlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false)
    private double targetAmount; // ₹ total to accumulate

    @Column(nullable = false)
    private int tranchesCount;

    @Column(nullable = false, length = 16)
    private String mode; // SIP | PRICE_LADDER | SIGNAL_GATED

    @Column(nullable = false, length = 16)
    private String status; // ACTIVE | PAUSED | COMPLETED | CANCELLED

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDate startDate;
    private LocalDate endDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "ACTIVE";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
