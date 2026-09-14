package com.example.trading.portfolio.dividend;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A dividend event announced (or received) for a held stock. See SPEC.md §11.
 */
@Entity
@Table(name = "dividend_event", indexes = {
        @Index(name = "idx_div_symbol", columnList = "symbol"),
        @Index(name = "idx_div_ex_date", columnList = "exDate")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DividendEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = false)
    private LocalDate exDate;

    private LocalDate recordDate;

    @Column(nullable = false)
    private double amountPerShare;

    @Column(nullable = false, length = 16)
    private String type; // INTERIM | FINAL | SPECIAL

    @Column(nullable = false, length = 16)
    private String status; // ANNOUNCED | RECEIVED | SKIPPED

    /** Actual cash received (per-share × quantity held on record date). Populated when status = RECEIVED. */
    private Double totalReceived;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "ANNOUNCED";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
