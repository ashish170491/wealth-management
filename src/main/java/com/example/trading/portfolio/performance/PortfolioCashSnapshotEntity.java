package com.example.trading.portfolio.performance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The broker's available equity cash on one day (SPEC §46.4).
 *
 * <p>Captured at the 15:00 snapshot from Kite's {@code /user/margins}, which the app had been
 * able to call since day one and never surfaced. Stored so the page can show cash with an
 * "as of" date rather than making a broker call on load. One row per day; a re-run replaces it.
 */
@Entity
@Table(name = "portfolio_cash_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_cash_snapshot_date", columnNames = {"snapshot_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioCashSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** {@code equity.available.cash} - what can be deployed today. */
    private Double availableCash;

    /** {@code equity.net} - available after today's utilisation; null when the payload lacks it. */
    private Double netCash;

    private LocalDateTime capturedAt;
}
