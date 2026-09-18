package com.example.trading.earnings;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One quarter's reported figures for one company, as filed (SPEC.md §50).
 *
 * <h2>Why this table exists</h2>
 * A quarterly result is the only regular, scheduled, company-specific event that can confirm or
 * break a multi-year thesis on <em>evidence</em> rather than on price. Until this table existed
 * the app fetched these figures from NSE on every screening run and threw them away: nothing
 * could answer "what did this company report, and when", nothing noticed a result <em>landing</em>,
 * and the only surviving read — the trend-break proxy in {@code NseDataService} — was recomputed
 * live on every email, holdings-only, negative-only and measured by nothing.
 *
 * <p>That mattered because the app's thesis monitor (§6.2) is a drift in the multibagger
 * composite, and §40.2 records that the composite gives <b>59% of its weight to price
 * behaviour</b>. A thesis alarm built on it is, in the main, a price alarm. This table is the
 * other half: what the business actually did.
 *
 * <h2>The key is the bare NSE trading symbol</h2>
 * Unlike {@code annual_fundamentals} (keyed {@code NSE:RELIANCE}), rows here are keyed on the
 * bare symbol — {@code RELIANCE}. A filing is a fact about the <em>company</em>, not about a
 * listing venue, and the feed it comes from is NSE's alone, so the exchange prefix carries no
 * information here and only creates the B-061 resolution problem for a reader to solve. Callers
 * normalise through {@code SymbolVariants.base()}, which collapses all three spellings to one.
 *
 * <h2>Every figure is nullable, and the basis is stored</h2>
 * A missing line item stays missing — a zero would read as "no finance cost" or "no tax" and
 * invert a ratio (SPEC §21 rule 7). {@link #consolidated} is stored per row because a series
 * that mixes consolidated and standalone manufactures a collapse and a recovery that never
 * happened (Gotcha 73); {@link QuarterlyResultRead} refuses to compare two quarters filed on
 * different bases rather than converting between them.
 *
 * <p>Amounts are in ₹ crore, matching {@code NseDataService.QuarterlyResult}.
 */
@Entity
@Table(name = "quarterly_results",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_quarterly_results_symbol_quarter",
                columnNames = {"symbol", "quarterEnd"}),
        indexes = {
                @Index(name = "idx_quarterly_results_symbol", columnList = "symbol"),
                @Index(name = "idx_quarterly_results_available", columnList = "availableFrom")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuarterlyResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bare NSE trading symbol, upper case — {@code INFY}, never {@code NSE:INFY}. */
    @Column(nullable = false, length = 32)
    private String symbol;

    /** The quarter's end date, from the filing's {@code qe_Date}. The natural key with symbol. */
    @Column(nullable = false)
    private LocalDate quarterEnd;

    /** Indian fiscal label derived from {@link #quarterEnd}, e.g. {@code Q1 FY27}. */
    @Column(length = 16)
    private String fiscalLabel;

    // ---- Reported figures (₹ crore, except eps and the margins)
    private Double revenue;
    private Double profit;
    private Double eps;
    private Double operatingProfit;
    private Double operatingMargin;
    private Double netMargin;
    private Double grossMargin;
    private Double financeCost;
    private Double depreciation;
    private Double tax;
    private Double totalExpenses;

    // ---- Filing metadata

    /**
     * True for a consolidated filing, false for standalone, null when NSE did not say.
     *
     * <p>Load-bearing: standalone revenue can be half the group figure (Gotcha 73 measured this
     * on RELIANCE), so a QoQ or YoY growth rate taken across a basis change is fiction that every
     * downstream verdict reads as real.
     */
    private Boolean consolidated;

    /** True when the filing declares itself audited. Informational; nothing gates on it. */
    private Boolean audited;

    /**
     * When these figures became <b>public</b> — the filing's own {@code broadcast_Date}.
     *
     * <p>Not the quarter end. A quarter ending 30-Jun is not public on 30-Jun: SEBI LODR Reg
     * 33(3)(a) allows 45 days and companies use them. Filing a result under its period end would
     * leak up to six weeks of look-ahead in the flattering direction — the lens would "know" the
     * number before the price moved — which is the bias {@code annual_fundamentals.available_from}
     * was introduced to remove (§32.6, Gotcha 100). This column is what makes a point-in-time
     * back-test of any result-based signal possible at all.
     */
    private LocalDate availableFrom;

    /**
     * True when {@link #availableFrom} is a conservative assumption rather than a filed date.
     *
     * <p>An assumption must never be readable as a fact. The integrated-filing feed carries a
     * real broadcast date, so this should be false for everything captured live; it exists for
     * rows whose date could not be parsed, and for any future importer.
     */
    private Boolean availableFromEstimated;

    /** NSE's own filing sequence id, kept so a row can be traced back to the filing. */
    @Column(length = 32)
    private String filingSeqId;

    /**
     * True when NSE marked this filing a revision of an earlier one.
     *
     * <p>A restatement is itself a management-quality signal and must not be silently absorbed
     * into the figures it replaces; the row is updated and this flag records that it happened.
     */
    private Boolean revised;

    @Column(length = 500)
    private String revisionRemark;

    /** Where the row came from. Only {@code INTEGRATED_FILING} today. */
    @Column(length = 32)
    private String source;

    // ---- Event bookkeeping

    /**
     * When this app first saw the row. Distinct from {@link #availableFrom}: the company
     * published on one date and we read it on another, and conflating them would let a backfill
     * of old quarters look like a burst of fresh results.
     */
    private LocalDateTime firstSeenAt;

    /**
     * When this result was reported to the investor as new, if it ever was.
     *
     * <p>Null means never announced. This is the persisted dedup §6.4 listed as out of scope for
     * thesis-drift: without it the daily email re-announces the same quarter for six weeks, and a
     * reader who is shown the same alert forty times has been trained to ignore it.
     */
    private LocalDateTime announcedAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (firstSeenAt == null) firstSeenAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
