package com.example.trading.multibagger;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing the multibagger screening score for a stock.
 * Scores each dimension (0-100) and produces a weighted composite score.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultibaggerScore {

    private String symbol;                    // NSE:RELIANCE
    private String tradingSymbol;             // RELIANCE
    private String industry;                  // Sector/Industry name
    private double currentPrice;
    private Double marketCapCrores;           // Market cap in crores

    // Dimension scores (0-100 each)
    private int technicalMomentumScore;       // Weekly/Monthly trend, EMA, RSI
    private int volumeAccumulationScore;      // Volume surge patterns, delivery %
    private int relativeStrengthScore;        // Performance vs Nifty 50
    private int priceStructureScore;          // Base building, higher lows, near 52w high
    // The four fundamental dimensions are nullable: null means "we could not measure this
    // stock on this dimension", which is deliberately distinct from a mid-range score. The
    // composite drops nulls and renormalises the remaining weights rather than substituting
    // a neutral value — see MultibaggerScreenerService.weightedComposite.
    private Integer valuationScore;               // PE vs sector PE, undervaluation
    private Integer institutionalInterestScore;   // FII/DII holding trend
    private Integer sectorTailwindScore;          // Sector momentum and rotation
    private Integer financialQualityScore;        // Balance-sheet/cash-flow quality (SPEC §6)

    // Composite
    private int compositeScore;               // Weighted sum (0-100)
    /**
     * Cross-sectional rank within this screening run: 100 = best in universe, 0 = worst.
     * Ties share the best position's rank. Null for single-stock screens where there is no
     * universe to rank against. Unlike the composite, this is immune to score-scale drift.
     */
    private Double percentileRank;
    private String grade;                     // A+, A, B+, B, C (based on composite)
    private String verdict;                   // STRONG_MULTIBAGGER, POTENTIAL_MULTIBAGGER, WATCHLIST, AVOID

    // Market cap category
    private String marketCapCategory;         // SMALL_CAP, MID_CAP, LARGE_CAP

    // Key metrics for display
    private Double weeklyRsi;
    private Double monthlyRsi;

    /** 20-day low — immediate support. Null when the run had fewer than 20 candles. */
    private Double support20d;

    /** ATR-14, the stock's normal daily range. Null when fewer than 15 candles. */
    private Double atr14;

    /** 50-day average close — anchors the deepest entry rung. Null below 50 candles. */
    private Double ema50;
    private Double relativeStrengthVsNifty;   // RS ratio
    private Double peDeviation;               // PE deviation from sector
    private Double priceVs52WeekHigh;         // % below 52-week high
    private Double priceVs52WeekLow;          // % above 52-week low
    private Double avgVolumeRatio;            // Recent volume vs average
    private Double weeklyEmaSlope;            // Weekly EMA trend slope

    // Reasons for the score
    @Builder.Default
    private List<String> bullishFactors = new ArrayList<>();
    @Builder.Default
    private List<String> bearishFactors = new ArrayList<>();

    // Earnings growth data
    private String earningsGrowthVerdict;    // STRONG_GROWTH, MODERATE_GROWTH, STAGNANT, DECLINING
    private Double yoyRevenueGrowth;
    private Double yoyProfitGrowth;
    private Double latestNetMargin;

    // Insider activity data
    private String insiderSignal;            // STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL
    private Double promoterHoldingChange;    // change in promoter holding %
    private boolean fiiIncreasing;
    // Latest-quarter holding levels (SPEC §12.5, persisted since 2026-09-09 so the screener can
    // show them). Null when the shareholding pattern was not available — never 0%.
    private Double promoterHoldingPct;
    private Double fiiHoldingPct;
    private Double diiHoldingPct;

    // Financial quality (balance-sheet / cash-flow)
    private String financialQualityVerdict;   // HIGH_QUALITY, DECENT, AVERAGE, WEAK, HIGH_RISK
    private Double interestCoverage;          // OpProfit / FinanceCost
    private Double ocfToProfitRatio;          // cash-flow-to-profit ratio (accounting quality)
    private Double promoterPledgePercent;     // leverage-via-equity proxy

    // Reverse-DCF (SPEC §12.5 intrinsic-valuation sanity check)
    private String dcfVerdict;                // DEEPLY_UNDERVALUED / UNDERVALUED / FAIRLY_VALUED / EXPENSIVE / EXTREMELY_EXPENSIVE / NOT_APPLICABLE
    private Double dcfImpliedGrowthPercent;   // growth rate implied by today's price
    private Double dcfHistoricalGrowthPercent;// 2-yr profit CAGR for comparison
    private Double dcfExpectationGapPercent;  // implied − historical

    // Wealth signals (SPEC §12.7 — long-term compounding quality)
    private Double grossMarginPercent;        // latest gross margin (pricing power); null for banks
    private Double grossMarginTrend;          // latest − ~1yr-ago (percentage points)
    private Double pegRatio;                  // PE / profit-growth% (growth-adjusted valuation)
    private Double deliveryPercent;           // delivery-to-traded % (genuine accumulation)
    private Integer earningsConsistencyScore; // 0-100, higher = steadier compounder

    // Capital efficiency (SPEC §12.8 — from NSE annual XBRL balance sheet)
    private Double rocePercent;               // return on capital employed
    private Double roePercent;                // return on equity
    private Double roaPercent;                // return on assets (headline metric for banks)
    private Double debtToEquity;              // total borrowings / equity
    private Double cashConversionRatio;       // operating cash flow / net profit
    private String capitalEfficiencyVerdict;  // HIGH_QUALITY_COMPOUNDER / SOLID / AVERAGE / WEAK / POOR / NA

    // Capex cycle (SPEC §31, F4) — capacity being built now, which the P&L cannot show
    // for another 12-24 months. Null throughout for banks/financials (NA_FINANCIAL).
    private Double cwipIntensityPercent;      // capital work-in-progress / net block
    private Double capexToDepreciation;       // >2 investing for growth, <0.8 harvesting
    private String capexVerdict;              // EXPANSION_UNDERWAY / INVESTING / STEADY / HARVESTING / NA_FINANCIAL / NO_DATA
    private Integer capexScore;               // 0-100 rendering of the verdict, for IC

    // Long-horizon fundamentals (SPEC §32, F5). Null means the stock has no imported
    // history — nothing was checked, which is not the same as nothing being wrong.
    private String turnaroundVerdict;         // TURNAROUND_CANDIDATE / NOT_TURNING / INSUFFICIENT_HISTORY
    private String forensicFlags;             // CODE:severity;CODE:severity, or null when clean

    // Buyability / liquidity (SPEC §12.9, F7). Execution properties, NOT quality
    // properties — they deliberately do not touch the composite. A thin stock can be an
    // excellent business; it just cannot be accumulated at sane impact, which is a
    // different fact and belongs in a different column.
    private Double liquidityAdv20d;           // 20-day average traded value, rupees
    private String liquidityTier;             // LIQUID / MODERATE / THIN / UNKNOWN
    private Integer circuitDaysLast60;        // days locked at a circuit band in last 60 (null = unmeasurable)

    // Is this stock already in holdings?
    private boolean inHoldings;
    private Double holdingsPnlPercent;

    /**
     * Under-Discovery score 0-100 (SPEC §12.10, F2) — small + under-owned + under-followed
     * + quietly accumulating. A <b>lens</b>, not a bonus: it never enters the composite,
     * because its ingredients (institutional holding, market-cap tier) are already scored
     * there and adding them again would double-count.
     *
     * <p>Null means not computed — either the stock failed the quality gate (under-discovered
     * junk is still junk) or its shareholding data was unavailable. Null is not zero.
     */
    private Integer underDiscoveryScore;

    // Insider Pulse (SPEC §28) — daily SEBI PIT open-market trades. Shadow-mode by default:
    // computed and persisted, but contributes no points until its IC is measured.
    private String insiderPulseVerdict;
    private Double insiderNetBuy90dPct;
    private Integer insiderPulseScore;        // 0-100 rendering of the verdict, for IC

    private LocalDateTime scoredAt;

    // Liquidity band thresholds in rupees of 20-day average traded value.
    public static final double LIQUID_MIN = 5_00_00_000d;   // Rs 5 crore
    public static final double MODERATE_MIN = 50_00_000d;   // Rs 50 lakh

    /**
     * Classify 20-day average traded value into a buyability tier.
     * Returns UNKNOWN (never a tier) when the value could not be measured.
     */
    public static String classifyLiquidity(Double adv20d) {
        if (adv20d == null || adv20d <= 0) return "UNKNOWN";
        if (adv20d >= LIQUID_MIN) return "LIQUID";
        if (adv20d >= MODERATE_MIN) return "MODERATE";
        return "THIN";
    }

    /**
     * Trading days needed to build {@code positionValue} rupees while taking no more than
     * {@code participationRate} of daily traded value. This is the number that makes
     * "thin" concrete for a retail investor: a stock trading Rs 20 lakh/day takes 50 days
     * to accumulate Rs 1 lakh at 10% participation.
     *
     * @return null when liquidity is unknown — the caller must render that as "unknown",
     *         never as 0 days (which would read as "instant").
     */
    public static Integer daysToBuild(Double adv20d, double positionValue, double participationRate) {
        if (adv20d == null || adv20d <= 0 || positionValue <= 0 || participationRate <= 0) return null;
        return (int) Math.ceil(positionValue / (adv20d * participationRate));
    }

    /**
     * Calculate grade from composite score.
     */
    public static String calculateGrade(int score) {
        if (score >= 85) return "A+";
        if (score >= 75) return "A";
        if (score >= 65) return "B+";
        if (score >= 55) return "B";
        if (score >= 45) return "C+";
        if (score >= 35) return "C";
        return "D";
    }

    /**
     * Calculate verdict from composite score.
     */
    public static String calculateVerdict(int score) {
        if (score >= 80) return "STRONG_MULTIBAGGER";
        if (score >= 65) return "POTENTIAL_MULTIBAGGER";
        if (score >= 50) return "WATCHLIST";
        if (score >= 35) return "MONITOR";
        return "AVOID";
    }

    /**
     * Classify market cap.
     */
    public static String classifyMarketCap(Double marketCapCrores, double smallCapMax, double midCapMax) {
        if (marketCapCrores == null || marketCapCrores <= 0) return "UNKNOWN";
        if (marketCapCrores <= smallCapMax) return "SMALL_CAP";
        if (marketCapCrores <= midCapMax) return "MID_CAP";
        return "LARGE_CAP";
    }
}
