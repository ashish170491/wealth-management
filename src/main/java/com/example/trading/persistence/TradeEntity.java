package com.example.trading.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String tradeType; // INTRADAY, DELIVERY
    private String action; // BUY, SELL
    private int quantity;
    private double entryPrice;
    private double exitPrice;
    private double stopLoss;
    private double target;
    private double realizedPnL;
    private String orderId;
    private String status; // OPEN, COMPLETED, CANCELLED, FAILED
    private String failureReason;
    private boolean paperTrade;
    private boolean exitOrder; // true if this is an exit/close order (not a new entry)

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
