package com.example.trading.funds;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One mutual fund position the investor holds, from Kite {@code /mf/holdings} (SPEC §52.3).
 *
 * <p><b>{@code units} is a decimal and must never become an int.</b> Kite returns fractional units
 * — the documented samples are {@code 382.488} and {@code 1.334} — and truncating the second to
 * {@code 1} is a 25% error on that position. That single fact is the strongest of the three reasons
 * funds are not rows in {@code holdings}, whose {@code quantity} is {@code private int}.
 *
 * <p><b>Keyed on (isin, folio), because one scheme can be held in several folios.</b> An investor
 * who bought the same fund through two routes has two positions, and merging them loses the ability
 * to reconcile against the broker.
 *
 * <p><b>Soft delete, deliberately unlike {@code holdings}.</b> B-016 hard-deletes exited stocks
 * because a stale equity row pollutes every report. Here the history <em>is</em> the feature: a
 * fund sold last year is part of the record of what this portfolio did. Reads filter on
 * {@link #active}.
 *
 * <p><b>P&amp;L is computed here, not read from Kite.</b> Both documented samples return
 * {@code pnl: 0} while the average and last prices differ, so the field cannot be trusted. And
 * {@code averagePrice} is itself nullable: Kite returns {@code 0.0} for units transferred in rather
 * than bought, which is <em>cost unknown</em> and never <em>free</em> — a zero cost basis would
 * report an infinite gain (Gotcha 21, 22).
 */
@Entity
@Table(name = "mf_holding",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_mf_holding_isin_folio", columnNames = {"isin", "folio"}),
        indexes = {
                @Index(name = "idx_mf_holding_active", columnList = "active"),
                @Index(name = "idx_mf_holding_scheme", columnList = "amfiSchemeCode")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MutualFundHoldingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kite sends this as {@code tradingsymbol}. It is an ISIN, and it is not unique in AMFI. */
    @Column(nullable = false, length = 24)
    private String isin;

    /**
     * Nullable in the broker payload; normalised to a single dash on write so the unique
     * constraint still separates two genuine folios rather than collapsing them.
     */
    @Column(nullable = false, length = 40)
    private String folio;

    /** The broker spelling of the fund name — a fourth vocabulary, kept for reconciliation. */
    @Column(length = 300)
    private String brokerFundName;

    /** Null when no AMFI scheme could be matched. Never guessed. */
    private Long amfiSchemeCode;

    /** Which lookup answered, so a reading can be traced rather than assumed (Gotcha 84). */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SchemeResolvedFrom schemeResolvedFrom;

    /** Fractional. Never an int. */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal units;

    /** Null when the broker reports no cost basis — unknown, never free. */
    @Column(precision = 18, scale = 6)
    private BigDecimal averagePrice;

    @Column(precision = 18, scale = 6)
    private BigDecimal lastPrice;

    /** The date of the NAV the broker quoted. An empty string there is null, not a date. */
    private LocalDate lastPriceDate;

    @Column(precision = 18, scale = 2)
    private BigDecimal investedValue;

    @Column(precision = 18, scale = 2)
    private BigDecimal currentValue;

    /** Computed here. Null when the cost basis is unknown — not zero. */
    @Column(precision = 18, scale = 2)
    private BigDecimal pnl;

    @Column(precision = 10, scale = 4)
    private BigDecimal pnlPercent;

    @Column(precision = 18, scale = 4)
    private BigDecimal pledgedUnits;

    @Column(nullable = false)
    private boolean active;

    private LocalDate exitedOn;

    private LocalDateTime lastSyncedAt;

    /** How a holding was matched to an AMFI scheme. {@code UNRESOLVED} is reported, never guessed. */
    public enum SchemeResolvedFrom {
        ISIN_GROWTH,
        ISIN_REINVEST,
        NAME_MATCH,
        UNRESOLVED
    }
}
