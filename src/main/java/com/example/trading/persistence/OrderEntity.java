package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "broker_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String brokerOrderId;
    private String symbol;
    private String transactionType; // BUY, SELL
    private String orderType; // MARKET, LIMIT, SL-M, SL-L
    private int quantity;
    private double price;
    private double triggerPrice;
    private String status; // PENDING, COMPLETE, CANCELLED, REJECTED

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
