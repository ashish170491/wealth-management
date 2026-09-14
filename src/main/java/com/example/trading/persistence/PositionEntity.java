package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "active_positions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String action; // BUY or SELL
    private int quantity;
    private double averageEntryPrice;
    private double currentPrice;
    private double stopLoss;
    private double target;
    private double unrealizedPnL;
    private double realizedPnL;
    private String status; // OPEN, CLOSED
    private String tradeId; // Link to original trade

    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private LocalDateTime lastUpdatedAt;

    // Position management fields for trailing stop and profit tracking
    private double peakPrice;           // Highest (BUY) or lowest (SELL) price since entry
    private double originalStopLoss;    // Original SL before any adjustments
    private boolean breakevenActivated; // True if SL moved to breakeven
    private boolean trailingActivated;  // True if trailing stop is active

    // Partial profit booking fields
    private boolean partialProfitBooked; // True if partial profit has been booked
    private LocalDateTime partialProfitBookedAt; // When partial profit was booked
    private int originalQuantity;        // Original quantity before partial exit
}
