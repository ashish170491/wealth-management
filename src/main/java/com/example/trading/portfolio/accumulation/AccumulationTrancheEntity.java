package com.example.trading.portfolio.accumulation;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One tranche of an accumulation plan.
 * For SIP mode: {@link #triggerDate} carries the scheduled date.
 * For PRICE_LADDER mode: {@link #triggerPrice} carries the limit price.
 * For SIGNAL_GATED mode: {@link #triggerSignal} carries the named signal (e.g. "BREAKOUT", "SECTOR_REVERSAL").
 * See SPEC.md §8.
 */
@Entity
@Table(name = "accumulation_tranche", indexes = {
        @Index(name = "idx_acc_tr_plan", columnList = "planId"),
        @Index(name = "idx_acc_tr_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccumulationTrancheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long planId;

    @Column(nullable = false)
    private int trancheNumber;

    @Column(nullable = false)
    private double amount; // ₹ planned for this tranche

    private LocalDate triggerDate;
    private Double triggerPrice;
    @Column(length = 32)
    private String triggerSignal;

    @Column(nullable = false, length = 16)
    private String status; // PENDING | FILLED | SKIPPED | CANCELLED

    private Integer filledQuantity;
    private Double filledPrice;
    private LocalDate filledDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
