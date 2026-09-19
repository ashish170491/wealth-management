package com.example.trading.watchlist;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One watchlist row as the dashboard sees it (SPEC §37). Every measured number is a wrapper —
 * null on the wire means "not measured" and the UI renders it as such, never as 0.
 */
public record WatchlistItemView(
        String symbol,
        String tradingSymbol,
        boolean active,
        String source,
        String addedNote,
        LocalDate addedOn,
        Long daysWatched,
        Boolean inHoldings,
        boolean inUniverse,

        Double priceAtAdd,
        Double currentPrice,
        Double dayChangePercent,
        Double returnSinceAddPct,
        Double niftyAtAdd,
        Double niftyNow,
        LocalDate niftyAsOf,
        Double niftyReturnPct,
        Double excessReturnPct,

        String sector,

        Integer qualityScore,
        String qualityGrade,
        String qualityVerdict,
        LocalDate qualityAsOf,

        /**
         * "Can this business compound?" (SPEC 41), from the shared {@code CompoundingLensService}
         * so the watchlist, screener, stock page and portfolio cannot disagree (SPEC 41.5).
         *
         * <p>Field names match what {@code compounding.js} already reads, so this table reuses the
         * screener's renderer rather than growing a second one. Null throughout means the stock has
         * never been screened - the UI shows the "not measured" marker, never a failing verdict.
         *
         * <p>Note this can be present while {@code qualityScore} is not, and vice versa: quality
         * comes from the newest screening row for this exact symbol at any age, the lens from the
         * newest row within 400 days across symbol spellings. Both are stamped, so a difference is
         * legible rather than mysterious.
         */
        String compounding,
        String compoundingReason,
        Integer compoundingPassed,
        Integer compoundingApplicable,
        Integer compoundingYearsOfAccounts,
        LocalDate compoundingAsOf,
        Integer adhocQualityScore,
        LocalDateTime adhocQualityAt,

        Integer timingScore,
        String entrySignal,
        String signalReason,
        Double signalConfidence,
        Double rsi14,
        String trendDirection,
        Double ema20,
        Double ema50,
        Double suggestedEntry,
        Double suggestedStopLoss,
        Double suggestedTarget1,
        Double nearestSupport,
        Double nearestResistance,

        String decayVerdict,
        Integer decayDelta30,
        String financialQualityVerdict,
        String forensicFlags,
        String liquidityTier,
        String dcfVerdict,

        BuyTimingVerdict.Verdict verdict,
        String verdictReason,
        boolean qualityMeasured,
        boolean timingMeasured,
        List<String> notMeasured,

        LocalDateTime lastAnalyzedAt,
        List<SeriesPoint> series,

        LocalDate removedOn,
        Double priceAtRemoval,
        Double returnWhileWatchedPct,

        /**
         * Suggested fresh entry (SPEC §12.12), from the same rule the screener uses so one stock
         * cannot show two different entry levels on two screens. Null is a finding — an AVOID row
         * deliberately has no price.
         */
        Double suggestedEntryPrice,
        String suggestedEntryBasis,
        String suggestedEntryReason,
        /** The full ladder (SPEC §12.12). Empty when no plan can honestly be given. */
        java.util.List<com.example.trading.multibagger.SuggestedEntry.Rung> suggestedEntryRungs,
        /** What to do if the lower rungs never fill; null for a buy-now plan. */
        String suggestedEntryFallback,

        /**
         * Macro and geopolitical exposure (SPEC §48).
         *
         * <p>Field names match what {@code macro-cells.js} already reads, so this table reuses the
         * portfolio's renderer verbatim rather than growing a second one. A null verdict means the
         * exposure map has no rule for this business - never a finding about the company, and
         * never the same thing as {@code NOT_EXPOSED}, which is a measured "nothing applies".
         */
        String macroExposure,
        String macroExposureStrength,
        java.util.List<String> macroExposureReasons,
        Integer macroExposureEvents,
        String macroExposureFrom,

        /**
         * Who else is quoting a price target on this stock (SPEC 49.15).
         *
         * <p>Field names match what {@code analyst-cells.js} already reads, so this table reuses
         * the portfolio's renderer verbatim rather than growing a second one - and so the firm
         * count here can never disagree with the one on My Portfolio (Gotcha 85).
         *
         * <p>{@code analystHouses} is an {@code Integer} and not an {@code int} on purpose:
         * <b>null means the ledger was not read, 0 means it was read and nothing is running.</b>
         * A primitive would collapse those two, and the second is a real measurement while the
         * first is the absence of one (SPEC 21 rule 7). Note a 0 here is a fact about what
         * reaches this app's feeds, never about whether the company is covered (SPEC 49.7).
         *
         * <p>Contributes zero points to any score.
         */
        Integer analystHouses,
        java.util.List<String> analystHouseNames,
        Integer analystOpenTargets,
        Double analystMedianTarget,
        Double analystHighestTarget,
        Double analystLowestTarget,
        Double analystUpsidePct,
        /** The price the upside was measured from - the ledger's stored close, not the live one. */
        Double analystPriceAsStored,
        LocalDate analystPriceAsOf,
        Integer analystHousesEver,
        java.util.List<String> analystHouseNamesEver,
        Integer analystTargetsEver,
        LocalDate analystLastCallOn,
        String analystTargetsFrom,
        String analystNote,
        /**
         * Live targets the share price has already passed (SPEC 49.16). Excluded from the median
         * and the upside, counted here so the absence of a figure can be explained.
         */
        Integer analystOvertaken,

        // ---- Policy-backed themes (SPEC 51.5) ------------------------------------------------
        // The same four names the screening row map and the holdings row carry, so one renderer
        // serves every surface (Gotcha 85). An EMPTY list means the map was consulted and names
        // no tracked theme - a finding. A NULL list means the lookup never ran and the cell must
        // draw the unmeasured marker. Two facts, two renderings (Gotcha 121).
        //
        // Contribute zero points to any score.
        java.util.List<String> themes,
        java.util.List<String> themeLabels,
        java.util.List<String> themePolicies,
        java.util.List<String> themeRoles) {

    public record SeriesPoint(LocalDate date, Double close) {}
}
