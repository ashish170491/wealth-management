package com.example.trading.watchlist;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity to store watchlist stock analysis results.
 * Unlike holdings, these are stocks we're tracking for potential entry.
 */
@Entity
@Table(name = "watchlist")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String symbol;              // NSE:RELIANCE, NSE:TCS

    private String tradingSymbol;       // RELIANCE, TCS
    private String exchange;            // NSE, BSE

    // Current price data
    private Double currentPrice;
    private Double previousClose;
    private Double dayChange;
    private Double dayChangePercent;
    private Double dayHigh;
    private Double dayLow;
    private Long volume;

    // Technical indicators
    private Double ema20;
    private Double ema50;
    private Double ema200;
    private Double rsi14;
    private Double atr14;
    private Double vwap;

    // Trend analysis
    private String trendDirection;      // BULLISH, BEARISH, SIDEWAYS
    private Boolean priceAboveEma20;
    private Boolean priceAboveEma50;
    private Boolean priceAboveEma200;
    private Boolean ema20AboveEma50;

    // Scores (0-100)
    private Integer technicalScore;
    private Integer momentumScore;
    private Integer overallScore;

    // Entry signals
    private String entrySignal;         // STRONG_BUY, BUY, HOLD, AVOID
    private String signalReason;        // Explanation for the signal
    private Double signalConfidence;    // 0-100

    // Suggested entry levels
    private Double suggestedEntry;      // Ideal entry price
    private Double suggestedStopLoss;   // Stop loss level (2x ATR below entry)
    private Double suggestedTarget1;    // Target 1 (2x ATR above entry)
    private Double suggestedTarget2;    // Target 2 (4x ATR above entry)
    private Double riskRewardRatio;     // Reward / Risk

    // Support/Resistance levels
    private Double nearestSupport;
    private Double nearestResistance;

    // Fundamental data
    private String industry;            // Sector/industry name
    private Double stockPe;             // Stock P/E ratio
    private Double industryPe;          // Sector average P/E
    private Double peDeviation;         // % deviation from sector PE
    private Double marketCap;           // In crores
    private Double priceToBook;         // Price-to-Book ratio
    private Double eps;                 // Earnings per share
    private Double dividendYield;       // Dividend yield %
    private String valuationRating;     // Undervalued, Fairly Valued, Overvalued, etc.
    private Integer fundamentalScore;   // 0-100 fundamental health score

    // Analysis notes
    @Column(length = 1000)
    private String analysisNotes;

    // ---- Tracking membership (SPEC §37). Set only by add / seed / remove — never by the
    // analysis path, which overwrites every field above on each run. All nullable wrappers.
    /** The day the investor put this stock on the list. Seeded rows use created_at. */
    private LocalDate addedOn;
    /** Kite last price at the moment of adding. Null for seeded rows — their return is "not measured", never 0. */
    private Double priceAtAdd;
    /** NSE:NIFTY 50 at the moment of adding, for the excess-return comparison. */
    private Double niftyAtAdd;
    /** The investor's one-line reason for watching. */
    @Column(length = 500)
    private String addedNote;
    /** MANUAL (added from the UI/API) or SEED (migrated from application.yml). */
    private String source;
    /** Soft-delete: false rows keep their history and are excluded from analysis and email. */
    private Boolean active;
    private LocalDate removedOn;
    private Double priceAtRemoval;
    /** Refreshed each analysis run: the stock is also in the holdings table right now. */
    private Boolean inHoldings;
    /** Ad-hoc composite from evaluateSingleStock on a manual refresh — never a screening row (Gotcha 50). */
    private Integer adhocQualityScore;
    private LocalDateTime adhocQualityAt;

    // Timestamps
    private LocalDateTime lastAnalyzedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isActiveRow() {
        return !Boolean.FALSE.equals(active);
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
