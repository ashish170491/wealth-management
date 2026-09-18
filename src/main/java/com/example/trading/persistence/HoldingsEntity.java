package com.example.trading.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "holdings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String symbol;           // NSE:RELIANCE

    @Column(unique = true)
    private String tradingSymbol;    // RELIANCE (unique to prevent duplicates across exchanges)
    private String exchange;         // NSE
    private String isin;             // Unique identifier

    private int quantity;
    private double averagePrice;     // Purchase average price
    private double currentPrice;     // Latest market price
    private double lastPrice;        // Last traded price
    private double closePrice;       // Previous close price

    private double investedValue;    // quantity * averagePrice
    private double currentValue;     // quantity * currentPrice
    private double pnl;              // Profit/Loss
    private double pnlPercent;       // P&L percentage
    private double dayChange;        // Today's change
    private double dayChangePercent; // Today's change percentage

    // Technical indicators (updated by analysis)
    private Double ema20;
    private Double ema50;
    private Double ema200;
    private Double rsi14;
    private Double atr14;            // Average True Range for stop loss
    private String trendDirection;   // BULLISH, BEARISH, SIDEWAYS

    // Analysis scores (0-100)
    private Integer technicalScore;
    private Integer momentumScore;
    private Integer overallScore;

    // Recommendation
    private String recommendation;   // STRONG_BUY, BUY, HOLD, SELL, STRONG_SELL
    private String analysisNotes;    // Detailed notes

    // Suggested levels
    private Double suggestedStopLoss;
    private Double suggestedTarget1;
    private Double suggestedTarget2;

    // Support & Resistance levels
    private Double pivotPoint;       // (20d High + 20d Low + Close) / 3
    private Double support1;         // 20-day low (immediate support)
    private Double support2;         // 50-day low (strong support)
    private Double resistance1;      // 20-day high (immediate resistance)
    private Double resistance2;      // 50-day high (strong resistance)

    // === Valuation Fields ===
    private String industry;                 // Industry/Sector name
    private Double stockPe;                  // Stock P/E ratio
    private Double industryPe;               // Industry average P/E ratio
    private Double peDeviation;              // % deviation from industry PE (positive = overvalued)
    private Double marketCap;                // Market capitalization in crores
    private Double bookValue;                // Book value per share
    private Double priceToBook;              // Price to Book ratio
    private Double eps;                      // Earnings per share
    private Double dividendYield;            // Dividend yield %
    private LocalDateTime lastValuationAt;   // When valuation was last updated

    private LocalDateTime purchaseDate;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime lastAnalyzedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ------------------------------------------------------------------ computed on read
    //
    // SPEC §6.6. These are NOT stored. They are the app's single canonical answer about this
    // stock, filled in by HoldingsViewDecorator on every read path, so that a screen cannot
    // accidentally answer a question its own way. Before this existed, the portfolio table and the
    // stock page rendered `recommendation` raw while a third surface reconciled it, and the
    // portfolio and screener each computed their own entry price (B-069).

    /** Signal to display, after a quality problem has been allowed to veto a buy. */
    @Transient
    private String displaySignal;

    /** Why the displayed signal differs from the stored one; null when it does not. */
    @Transient
    private String signalNote;

    /** "Still a good time to buy?" verdict, from the one shared rule table. */
    @Transient
    private String buyTimingVerdict;

    @Transient
    private String buyTimingReason;

    /** The shared entry ladder (SPEC §12.12) — the same rule the screener and watchlist use. */
    @Transient
    private java.util.List<com.example.trading.multibagger.SuggestedEntry.Rung> suggestedEntryRungs;

    @Transient
    private String suggestedEntryBasis;

    @Transient
    private String suggestedEntryReason;

    @Transient
    private String suggestedEntryFallback;

    /**
     * "Can this business compound?" (SPEC 41) - the same lens the screener column and the stock
     * page show, attached here so the portfolio cannot answer it differently (Gotcha 85).
     *
     * <p>Field names match what {@code compounding.js} already reads, so the portfolio table
     * reuses the screener's renderer rather than growing a second one. Null throughout means the
     * stock was never screened, which is not a failing verdict (Gotcha 21, 44).
     */
    @Transient
    private String compounding;

    @Transient
    private String compoundingReason;

    @Transient
    private Integer compoundingPassed;

    @Transient
    private Integer compoundingApplicable;

    /** How many years of accounts back the verdict. SPEC 41 reads the LATEST year only. */
    @Transient
    private Integer compoundingYearsOfAccounts;

    /** Which symbol spelling carried the screening history, so a reading can be traced. */
    @Transient
    private String compoundingFrom;

    // SPEC 46.5 - holding period from the tax ledger. Null days = nothing on file, never 0.
    @Transient
    private Integer daysHeld;

    /** TAX_LOTS / HOLDINGS_PURCHASE_DATE / UNKNOWN - what the days-held figure rests on. */
    @Transient
    private String holdingPeriodSource;

    @Transient
    private java.time.LocalDate firstBuyDate;

    @Transient
    private Integer ltcgEligibleQuantity;

    @Transient
    private Integer stcgQuantity;

    @Transient
    private Integer daysUntilNextLtcg;

    // SPEC 46.6 - young listings. Null throughout = not an IPO the tracker knows.
    @Transient
    private java.time.LocalDate ipoListingDate;

    @Transient
    private Double ipoIssuePrice;

    /** HYPE_WINDOW / WASHOUT / RECOVERING / BASE_FORMING / NOT_MEASURED (IpoLockIn.Stage). */
    @Transient
    private String ipoStage;

    @Transient
    private String ipoStageReason;

    /** The next lock-in expiry still ahead, if any - the date the next tranche of sellers is free. */
    @Transient
    private String ipoNextUnlockLabel;

    @Transient
    private java.time.LocalDate ipoNextUnlockDate;

    /** The resolved simple sector (B-096), so every screen groups the same way the drift engine does. */
    @Transient
    private String sector;

    // ---- Macro & geopolitical event exposure (SPEC 48.4) -------------------------------------
    // A RISK NOTE ON THE BUSINESS, not a signal. It changes no score, is not an input to
    // BuyTimingVerdict, and its vocabulary contains no instruction to transact. Attached here so
    // every holdings read path carries the same answer and no two screens can disagree.

    /** TAILWIND / HEADWIND / MIXED / NOT_EXPOSED / NOT_MEASURED. Null when never screened. */
    @Transient
    private String macroExposure;

    /** HIGH / MEDIUM / LOW. Null whenever there is no directional reading to size. */
    @Transient
    private String macroExposureStrength;

    /** One line per matching event, strongest first. Empty when nothing in the window applies. */
    @Transient
    private java.util.List<String> macroExposureReasons;

    /** How many events produced the reading. Null, never 0, when nothing was measured. */
    @Transient
    private Integer macroExposureEvents;

    /** Which symbol spelling answered, so a reading can be traced rather than assumed (Gotcha 84). */
    @Transient
    private String macroExposureFrom;

    // ---- Analyst target coverage (SPEC 49.14) -----------------------------------------------
    // WHO IS WATCHING THIS STOCK, not what this app thinks of it. Contributes zero points to any
    // score, is not an input to BuyTimingVerdict, and its vocabulary contains no instruction to
    // transact. Attached here so every holdings read path carries the same counts and no two
    // screens can disagree about how many firms cover one holding (SPEC 6.6, Gotcha 85).

    /**
     * Brokerages with a target still running on this stock.
     *
     * <p><b>Zero means counted zero</b> — the ledger was searched and nothing is live. Null means
     * the lookup did not run at all. The two are different facts and the UI draws them
     * differently (Gotcha 21, 121). Note what a zero here does NOT mean: coverage is bounded by
     * what reaches this app's feeds (SPEC 49.7), so "no house quoting it" is a statement about
     * the feed, never about whether analysts follow the company.
     */
    @Transient
    private Integer analystHouses;

    /** The firms, named. A count with no names cannot be checked or argued with. */
    @Transient
    private java.util.List<String> analystHouseNames;

    /** Live targets. Exceeds {@code analystHouses} when a firm has revised (B-041). */
    @Transient
    private Integer analystOpenTargets;

    /** Median of the live targets — never called a consensus (SPEC 49.8). */
    @Transient
    private Double analystMedianTarget;

    /** Upside from the last stored price to that median. Null, never 0, if either leg is missing. */
    @Transient
    private Double analystUpsidePct;

    /** Highest and lowest live target, so the spread of opinion is visible beside the median. */
    @Transient
    private Double analystHighestTarget;

    @Transient
    private Double analystLowestTarget;

    /**
     * The price the upside was measured from, and the day it was stored.
     *
     * <p>This is the ledger's own last-measured close, <b>not</b> {@code currentPrice} on this
     * row — they come from different passes and can differ. Carried so a screen can name the
     * basis instead of leaving a reader to recompute the percentage against the price column
     * beside it and conclude the app is wrong. A reading must be traceable (Gotcha 84's rule,
     * applied to a figure rather than to a symbol).
     */
    @Transient
    private Double analystPriceAsStored;

    @Transient
    private java.time.LocalDate analystPriceAsOf;

    /** Firms that have ever quoted a target here. Covered-but-quiet is not never-covered. */
    @Transient
    private Integer analystHousesEver;

    @Transient
    private java.util.List<String> analystHouseNamesEver;

    /** Every target on file for this stock, live or closed. */
    @Transient
    private Integer analystTargetsEver;

    /** The most recent call, so a stale book reads as stale rather than as current. */
    @Transient
    private java.time.LocalDate analystLastCallOn;

    /** Which symbol spelling carried the targets, so a reading can be traced (Gotcha 84). */
    @Transient
    private String analystTargetsFrom;

    /** The plain-English state: nothing on file, covered but quiet, or N firms running. */
    @Transient
    private String analystNote;

    // ---- Quarterly result (SPEC 50.5). Attached on every holdings read path by
    // HoldingsViewDecorator, so no screen can compute its own answer (SPEC 6.6). All nullable
    // wrappers: a primitive would collapse "no filed quarter captured" into a measured zero, and
    // those are different facts (Gotcha 21). Contributes zero points to any score.

    /** STRONG / IN_LINE / WEAK / CONCERNING / NOT_MEASURED. Never an instruction to transact. */
    @Transient
    private String resultVerdict;

    /** The quarter judged, e.g. {@code Q1 FY27}. */
    @Transient
    private String resultQuarter;

    /** One plain-English sentence, per SPEC 21 — the investor is not an analyst. */
    @Transient
    private String resultHeadline;

    /**
     * The day the company published, not the quarter end.
     *
     * <p>Carried so a screen can say how old the reading is. A result six weeks old is a
     * different thing from one that landed this morning, and the quarter end cannot tell them
     * apart (Gotcha 100).
     */
    @Transient
    private java.time.LocalDate resultPublishedOn;

    @Transient
    private Double resultRevenueYoyPercent;

    @Transient
    private Double resultProfitYoyPercent;

    /** Net-margin change in percentage points against the same quarter last year. */
    @Transient
    private Double resultMarginDeltaPp;

    /**
     * How many of the four checks produced an answer, and out of how many.
     *
     * <p>Both are carried so a surface can print "3 of 4" rather than implying a completed
     * screen — the discipline Gotcha 44 exists for, applied to a quarter.
     */
    @Transient
    private Integer resultMeasuredSignals;

    @Transient
    private Integer resultTotalSignals;

    /** True when the company restated figures it had already published. News in its own right. */
    @Transient
    private Boolean resultRevised;

    /** Which symbol spelling carried the filings, so a reading can be traced (Gotcha 84). */
    @Transient
    private String resultFrom;

    /** AWAITING_QUARTER_END / EXPECTED / PAST_DUE / NOT_MEASURED. */
    @Transient
    private String nextResultStatus;

    /** The expected-window sentence, including why it is a window and not a date. */
    @Transient
    private String nextResultText;

    /**
     * What today's move is worth on this position, in rupees - {@code dayChange} times quantity.
     *
     * <p>{@link #getDayChange()} is <b>per share</b> ({@code lastPrice - closePrice}, written in
     * {@code HoldingsAnalysisService}), and that is correct for {@link #getDayChangePercent()}
     * because the ratio is the same either way. It is <b>not</b> money, and summing it across a
     * portfolio produces a number in no unit at all - which is exactly what the landing page did:
     * on 2026-09-17 the Overview's "Today's Change" tile read <b>-Rs 52.23 (-0.02%)</b> on a book
     * that had actually gained <b>+Rs 4,607.90 (+1.90%)</b>. Wrong sign, 89x the magnitude, because
     * per-share deltas are dominated by share price rather than position size and roughly cancel
     * across thirty names: CPPLUS (1 share at Rs 3,375) contributed -Rs 172 while LCCPROJECT
     * (102 shares, +Rs 2,728 of real money) contributed +Rs 26.75. B-120, and the same family as
     * B-047 and B-113 - a figure quoted on a scale it was not measured on.
     *
     * <p>Derived here rather than at each call site so the portfolio total and the per-row figure
     * cannot drift apart (Gotcha 85), and stored nowhere: it is two columns and a multiplication.
     *
     * <p><b>Null, never zero, when there is no previous close.</b> A holding listed today, or one
     * whose close has not synced, has an <i>unknown</i> move - and a zero would silently drag a
     * portfolio total toward nothing while looking measured (SPEC 21 rule 7).
     */
    @Transient
    public Double getDayChangeValue() {
        if (closePrice <= 0 || currentPrice <= 0) return null;
        return (currentPrice - closePrice) * quantity;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
