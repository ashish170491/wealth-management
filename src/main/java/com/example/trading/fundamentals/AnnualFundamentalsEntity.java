package com.example.trading.fundamentals;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One financial year of fundamentals for one stock (SPEC.md §32).
 *
 * <p><b>Why this table exists.</b> NSE's integrated-filing feed reaches back only to
 * ~Mar-2025 (B-017), so the live XBRL pipeline can see about five quarters. Everything
 * that needs a decade — 10-year CAGRs, through-cycle margins, multi-year debt reduction,
 * share-count dilution — is invisible to it. Turnarounds and serial diluters are exactly
 * the categories that only show up over that horizon, and the second is the fraud shield
 * that widening the universe (§30) makes necessary.
 *
 * <p><b>Two writers, one table.</b> Rows arrive either from a user-supplied history import
 * ({@code source=IMPORT}) or from the annual XBRL pipeline as each new year files
 * ({@code source=XBRL}). The import is a one-time bridge for the pre-2025 past; from here
 * on the table maintains itself. An XBRL row wins over an IMPORT row for the same year,
 * because it came from the filing rather than a third-party rendering of it.
 *
 * <p><b>Every figure is nullable.</b> Different sources carry different fields, and a
 * missing line item must stay missing — a zero here would read as "no debt" or "no capex"
 * and quietly invert a verdict (SPEC §21 rule 7).
 *
 * <p>Amounts are in ₹ crore, matching {@code NseDataService.BalanceSheetData}.
 */
@Entity
@Table(name = "annual_fundamentals",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_annual_fundamentals_symbol_year", columnNames = {"symbol", "fiscalYear"}),
        indexes = @Index(name = "idx_annual_fundamentals_symbol", columnList = "symbol"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnualFundamentalsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Exchange-qualified symbol, e.g. {@code NSE:RELIANCE}. */
    @Column(nullable = false, length = 32)
    private String symbol;

    /** The year the statement ends in: FY2024-25 is stored as 2025. */
    @Column(nullable = false)
    private Integer fiscalYear;

    // ---- Profit & loss
    private Double sales;
    private Double operatingProfit;
    private Double netProfit;
    private Double interestCost;
    private Double depreciation;

    // ---- Balance sheet
    private Double borrowings;
    private Double equity;
    private Double totalAssets;
    private Double receivables;
    private Double capitalWorkInProgress;

    /**
     * Net block — property, plant and equipment after depreciation (B-048).
     *
     * <p>Stored per year because the capex ratio needs the <i>prior</i> year's figure and
     * NSE's filing carries no comparative balance sheet (B-034). Without this column
     * {@code capexToDepreciation} was permanently null in production, so the capex verdict
     * could only ever speak about CWIP intensity.
     */
    private Double netBlock;

    // ---- Cash flow
    private Double operatingCashFlow;

    /**
     * Shares outstanding, <b>in crore</b>. The dilution flag lives or dies on this field: a
     * company can grow sales for a decade and still hand you nothing if the share count
     * grew as fast.
     *
     * <p>The unit is enforced on write, not assumed. The XBRL path derives it as paid-up
     * capital / face value / 1e7; the CSV import normalises, because a user's sheet may
     * carry an absolute count. A mixed-unit series would show a 10-million-fold step and be
     * read as a colossal buyback or issue, so {@code ForensicScreenService} also refuses to
     * measure dilution across an implausible jump rather than reporting one (B-046).
     */
    private Double shareCount;

    /**
     * Whether this year's figures are consolidated (group) or standalone (parent only).
     *
     * <p>Load-bearing for any multi-year comparison. NSE's archive does not always carry both:
     * measured on RELIANCE, FY2022 exists <em>only</em> as a standalone filing while the years
     * either side are consolidated — and standalone revenue there is roughly half the group
     * figure. A series that mixes the two silently produces a collapse-then-recovery that never
     * happened, which every CAGR, margin trend, turnaround verdict and dilution check downstream
     * would read as real. Null on rows written before this was recorded, and on CSV imports where
     * the basis is whatever the user's sheet used.
     */
    private Boolean consolidated;

    /**
     * IMPORT (user-supplied history) or XBRL (this system's annual pipeline).
     *
     * <p>Deliberately <b>not</b> declared {@code nullable = false}. The annotation that was meant
     * for this field used to sit above {@code consolidated}'s javadoc, so Hibernate bound
     * {@code nullable=false, length=16} to a {@code Boolean} and this column got defaults. Since
     * the table is already populated, re-declaring it non-null now would be the B-026 failure in
     * the other direction — {@code ddl-auto=update} never relaxes a constraint, and adding one to
     * a populated column is rejected row by row. The invariant is enforced by the two writers.
     */
    @Column(length = 16)
    private String source;

    // ---- Point-in-time availability (SPEC §32.6)

    /**
     * The date these figures became public knowledge — the only field that makes a
     * fundamentals lens back-testable without look-ahead bias.
     *
     * <p><b>Why this is not the fiscal year.</b> A March-2024 year end is not public in March
     * 2024. SEBI LODR Reg 33 allows 60 days for audited annual results, and companies use them.
     * Scoring a past screening date with figures the market did not yet have is look-ahead bias,
     * and it biases in the flattering direction — the back-test "knew" the result before the
     * price moved. Any point-in-time evaluation must filter on this column, never on
     * {@link #fiscalYear}.
     *
     * <p>Populated from the filing's own broadcast date where the archive carries one; otherwise
     * estimated, and {@link #availableFromEstimated} says which. Null on rows written before this
     * column existed, and on CSV imports, where the export carries no filing date at all.
     */
    private java.time.LocalDate availableFrom;

    /**
     * True when {@link #availableFrom} is a conservative assumption rather than a filed date.
     *
     * <p>An assumed date must never be readable as a real one (Gotcha 21, 33, 68): a back-test
     * quoting a precise start date derived from a guess is exactly the confident-looking number
     * for data we do not have. Consumers may use an estimated date, but must be able to say so.
     */
    private Boolean availableFromEstimated;

    // ---- Capital allocation (SPEC §42)

    /**
     * Dividends paid during the year, ₹ crore. Retention is {@code 1 − payout}, and retention is
     * half the compounding arithmetic in SPEC §41.1 — a business grows intrinsic value at roughly
     * return on capital × the share of profit it reinvests.
     *
     * <p>Three things needed this and none of them had it: §41.4 names persisting payout as the
     * first thing that would improve the compounding lens; the capital-allocation record (§42) is
     * built on it; and {@code ForensicScreenService.isBonusOrSplit} documents that it errs toward
     * silence <em>because</em> "this system does not carry dividends per year on the history row"
     * — dividends suppress equity growth and the discriminator could not tell that apart from a
     * share issue.
     */
    private Double dividendsPaid;

    /**
     * Profit before tax, ₹ crore. Previously read from the filing only to reconstruct
     * {@link #operatingProfit} as PBT + finance costs, then discarded. Stored so return on
     * capital employed can be computed directly from a history row, and so the effective tax
     * rate is derivable (PBT − net profit) rather than being another fetch.
     */
    private Double profitBeforeTax;

    /**
     * Face value per share, ₹. A change in face value <b>is</b> a stock split.
     *
     * <p>{@link #shareCount} is already derived as paid-up capital ÷ face value, so this costs
     * nothing at parse time and it makes split detection exact rather than inferred from a ratio.
     * B-066 has to recognise a simple ratio inside a 0.05% tolerance (Gotcha 86) precisely because
     * it cannot see the corporate action itself; with face value on the row, a split is a fact
     * rather than a near-miss on a grid of fractions.
     */
    private Double faceValue;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Operating margin %, or null when either side is missing or sales are zero. */
    public Double operatingMarginPercent() {
        if (operatingProfit == null || sales == null || sales <= 0) return null;
        return operatingProfit / sales * 100.0;
    }

    /** Debt-to-equity, or null when unmeasurable. Equity <= 0 yields null, not infinity. */
    public Double debtToEquity() {
        if (borrowings == null || equity == null || equity <= 0) return null;
        return borrowings / equity;
    }
}
