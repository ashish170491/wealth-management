package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_execution_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyExecutionLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String strategyName;
    private String symbol;
    private String signalAction; // BUY, SELL, HOLD
    private String signalReason;
    private double confidence;
    private String executionStatus; // SIGNAL_GENERATED, RISK_PASSED, RISK_REJECTED, EXECUTED, FAILED

    @Column(columnDefinition = "TEXT")
    private String marketSnapshot; // JSON snapshot of indicators at time of signal

    private LocalDateTime timestamp;
}
