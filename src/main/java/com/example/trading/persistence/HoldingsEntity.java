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
