package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "holdings_history", indexes = {
    @Index(name = "idx_holdings_history_symbol_date", columnList = "symbol, recordDate")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingsHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private LocalDate recordDate;

    private int quantity;
    private double averagePrice;
    private double closePrice;
    private double pnl;
    private double pnlPercent;

    // Snapshot of analysis at that time
    private Integer technicalScore;
    private Integer overallScore;
    private String recommendation;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
