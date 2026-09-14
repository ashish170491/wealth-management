package com.example.trading.portfolio.performance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One daily close of a benchmark index (SPEC §46.2).
 *
 * <p>Exists so the portfolio page can draw "you versus the index" without a broker call on page
 * load (SPEC §27.4, §20 rule 7). Written once a day by the 15:00 holdings snapshot and, for
 * history, by the manual backfill. Kept as its own table rather than a column on
 * {@code holdings_history} because a benchmark has one close per day while that table has one
 * row per holding per day, and because a backfill of the index must not touch the record of
 * what was held.
 */
@Entity
@Table(name = "benchmark_daily_close",
        uniqueConstraints = @UniqueConstraint(name = "uk_benchmark_symbol_date", columnNames = {"symbol", "close_date"}),
        indexes = @Index(name = "idx_benchmark_symbol_date", columnList = "symbol, close_date"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkCloseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kite index symbol, e.g. {@code NSE:NIFTY 50} (Gotcha 1: the space is part of it). */
    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(name = "close_date", nullable = false)
    private LocalDate closeDate;

    @Column(nullable = false)
    private double close;

    /** {@code SNAPSHOT} (15:00 live price) or {@code BACKFILL} (daily candle). */
    @Column(length = 16)
    private String source;

    private LocalDateTime createdAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
