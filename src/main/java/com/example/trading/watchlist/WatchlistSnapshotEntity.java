package com.example.trading.watchlist;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row per tracked symbol per trading day (SPEC §37.4).
 *
 * <p>There is no daily-close table for stocks the investor does not own — {@code holdings_history}
 * covers holdings only and candles are a live Kite call — so this is what lets the watchlist page
 * draw a sparkline and quote "return since added" without touching the broker on page load.
 * Written by the 15:00 analysis run; the last 90 closes are also backfilled from the candles a
 * manual refresh already fetches (no extra call), with {@code niftyClose} null for those rows.
 * The verdict is stamped so the rule table's history can be audited later.
 */
@Entity
@Table(name = "watchlist_daily_snapshot",
        uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "snapshot_date"}),
        indexes = @Index(name = "idx_watchlist_snapshot_symbol", columnList = "symbol"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    private Double close;
    private Double niftyClose;
    private Integer timingScore;
    private Integer qualityScore;
    private String trendDirection;
    private String verdict;
    private Double returnSinceAddPct;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
