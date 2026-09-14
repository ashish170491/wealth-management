package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Persists multibagger screening scores for tracking over time.
 * One record per symbol per screening date.
 */
@Entity
@Table(name = "multibagger_scores",
       uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "screeningDate"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultibaggerScoreEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String symbol;                    // NSE:RELIANCE

    private String tradingSymbol;             // RELIANCE
    private String industry;

    @Column(nullable = false)
    private LocalDate screeningDate;

    private double currentPrice;
    private Double marketCapCrores;
    private String marketCapCategory;         // SMALL_CAP, MID_CAP, LARGE_CAP

    // Dimension scores (0-100)
    private int technicalMomentumScore;
    private int volumeAccumulationScore;
    private int relativeStrengthScore;
    private int priceStructureScore;
    // Nullable: null = the dimension was unmeasurable for this stock, which is distinct
    // from a mid-range score. Per-dimension IC already skips null sub-scores, so an
    // unmeasured dimension no longer masquerades as a real observation.
    private Integer valuationScore;
    private Integer institutionalInterestScore;
    private Integer sectorTailwindScore;
    // Wrapper type — column is added to an existing populated table, so must be nullable.
    // New rows always populate it (default 50 when data unavailable); historical rows stay null.
    private Integer financialQualityScore;    // SPEC §6 financial-quality dimension

    // Composite
    private int compositeScore;

    /**
     * Cross-sectional percentile rank within the screening run that produced this row
     * (100 = best). Nullable — rows written before 2026-08-22 have none, and Hibernate
     * ddl-auto=update cannot add a NOT NULL column to a populated table anyway
     * (see CLAUDE.md "Integer-Not-Null Migration Bug").
     */
    private Double percentileRank;
    private String grade;                     // A+, A, B+, B, C, D
    private String verdict;                   // STRONG_MULTIBAGGER, POTENTIAL_MULTIBAGGER, WATCHLIST, MONITOR, AVOID

    // Key metrics
    private Double weeklyRsi;
    private Double monthlyRsi;
    private Double relativeStrengthVsNifty;
    private Double peDeviation;
    private Double priceVs52WeekHigh;
    private Double priceVs52WeekLow;
    private Double avgVolumeRatio;
    private Double weeklyEmaSlope;

    /** 20-day low — immediate support. Null when the run had fewer than 20 candles. */
    private Double support20d;

    /** ATR-14, the stock's normal daily range. Null when fewer than 15 candles. */
    private Double atr14;

    /**
     * 50-day average of daily closes. Anchors the deepest rung of the entry ladder (SPEC §12.12)
     * because it is the level the entry verdict's own reason cites ("stretched above its average").
     * Null when the run had fewer than 50 candles.
     */
    private Double ema50;

    // Factors stored as text
    @Column(length = 2000)
    private String bullishFactors;            // Comma-separated

    @Column(length = 2000)
    private String bearishFactors;            // Comma-separated

    // Financial quality (from NseDataService.analyzeFinancialQuality)
    @Column(length = 32)
    private String financialQualityVerdict;   // HIGH_QUALITY, DECENT, AVERAGE, WEAK, HIGH_RISK
    private Double interestCoverage;
    private Double ocfToProfitRatio;
    private Double promoterPledgePercent;

    // Reverse-DCF (from IntrinsicValuationService) — all nullable (appended to existing table)
    @Column(length = 32)
    private String dcfVerdict;
    private Double dcfImpliedGrowthPercent;
    private Double dcfHistoricalGrowthPercent;
    private Double dcfExpectationGapPercent;

    // Wealth signals (SPEC §12.7) — all nullable wrappers (appended to existing populated table;
    // see bug #11 "Integer-Not-Null Migration" — never use primitive int/double here)
    private Double grossMarginPercent;        // latest gross margin % (null for banks)
    private Double grossMarginTrend;          // latest − ~1yr-ago (percentage points)
    private Double pegRatio;                  // PE / profit-growth%
    private Double deliveryPercent;           // delivery-to-traded %
    private Integer earningsConsistencyScore; // 0-100

    // Capital efficiency (SPEC §12.8 — NSE annual XBRL balance sheet) — all nullable wrappers
    private Double rocePercent;               // return on capital employed
    private Double roePercent;                // return on equity
    private Double roaPercent;                // return on assets (headline metric for banks)
    private Double debtToEquity;              // total borrowings / equity
    private Double cashConversionRatio;       // operating cash flow / net profit
    @Column(length = 32)
    private String capitalEfficiencyVerdict;  // HIGH_QUALITY_COMPOUNDER / SOLID / AVERAGE / WEAK / POOR / NA

    // Capex cycle (SPEC §31, F4) — all nullable wrappers; null for banks (NA_FINANCIAL)
    private Double cwipIntensityPercent;      // capital work-in-progress / net block
    private Double capexToDepreciation;       // >2 investing for growth, <0.8 harvesting
    @Column(length = 32)
    private String capexVerdict;              // EXPANSION_UNDERWAY / INVESTING / STEADY / HARVESTING / NA_FINANCIAL / NO_DATA
    /** 0-100 rendering of the capex verdict, so per-dimension IC can measure it (Gotcha 29). */
    private Integer capexScore;

    // Long-horizon fundamentals (SPEC §32, F5) — populated only for stocks with an
    // imported history. Null means "not checked", which is NOT the same as "clean".
    @Column(length = 32)
    private String turnaroundVerdict;         // TURNAROUND_CANDIDATE / NOT_TURNING / INSUFFICIENT_HISTORY
    /** Compact {@code CODE:severity;CODE:severity} list; null when no flag fired. */
    @Column(length = 255)
    private String forensicFlags;

    // Buyability / liquidity (SPEC §12.9, F7) — execution properties, not scored
    private Double liquidityAdv20d;           // 20-day average traded value (rupees)
    @Column(length = 16)
    private String liquidityTier;             // LIQUID / MODERATE / THIN / UNKNOWN
    private Integer circuitDaysLast60;

    // Under-discovery lens (SPEC §12.10, F2) — nullable: null = not computed, not zero
    private Integer underDiscoveryScore;

    // Insider Pulse (SPEC §28, F1) — daily SEBI PIT disclosures, shadow-mode by default
    @Column(length = 32)
    private String insiderPulseVerdict;       // STRONG_ACCUMULATION ... STRONG_DISTRIBUTION
    private Double insiderNetBuy90dPct;       // net open-market promoter buying, % of market cap
    /**
     * 0-100 rendering of the insider-pulse verdict. Exists so the signal is visible to
     * {@code RecommendationAccuracyService.computeDimensionIC}, which reads MULTIBAGGER
     * sub-scores as Integers off this table and cannot see a String verdict or the sidecar.
     */
    private Integer insiderPulseScore;

    // Growth and ownership (SPEC §12.5, 2026-09-09). These were computed on every run and consumed
    // by the earnings and insider bonuses, then thrown away — so the screener could show neither
    // the Growth pillar nor promoter skin-in-the-game, two of the first things an Indian investor
    // checks. Written from values the run already holds: zero extra NSE calls. All nullable
    // wrappers (bug 11 — the table is populated), and null means "not measured", never 0%.
    @Column(length = 32)
    private String earningsGrowthVerdict;     // STRONG_GROWTH / MODERATE_GROWTH / STAGNANT / DECLINING
    private Double yoyRevenueGrowth;          // latest quarter vs the same quarter a year ago, %
    private Double yoyProfitGrowth;
    private Double promoterHoldingPct;        // latest quarterly shareholding pattern
    private Double promoterHoldingChangePct;  // latest vs oldest quarter on file, percentage points
    private Double fiiHoldingPct;
    private Double diiHoldingPct;

    /**
     * Which version of the scoring configuration produced this row (SPEC §38.1), e.g.
     * {@code mb1-7a1c3e}. Nullable — rows written before 2026-08-29 have none, and a null
     * must be read as "unknown provenance", never as "the current engine".
     *
     * <p>Without this, a "63" from June and a "63" from September are different measurements
     * filed under the same name: B-019 scaled every composite by 15% for months and B-018
     * added a constant to every stock. Any IC panel, accuracy figure or future training set
     * that pools across a version boundary is pooling incomparable observations.
     */
    @Column(length = 32)
    private String scoringVersion;

    // Holdings integration
    private boolean inHoldings;
    private Double holdingsPnlPercent;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (screeningDate == null) {
            screeningDate = LocalDate.now();
        }
    }
}
