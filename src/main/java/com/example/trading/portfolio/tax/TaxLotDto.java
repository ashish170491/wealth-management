package com.example.trading.portfolio.tax;

import java.time.LocalDate;
import java.util.List;

/** API DTOs for Tax-Lot Tracking (SPEC.md §9). */
public final class TaxLotDto {

    private TaxLotDto() {}

    /** Snapshot of a single lot with live P&L and tax projection vs today's price. */
    public record LotView(
            Long id,
            String symbol,
            int originalQuantity,
            int remainingQuantity,
            double buyPrice,
            LocalDate buyDate,
            int daysHeld,
            int daysToLtcgCutoff,
            String gainTypeIfSoldToday,
            double currentPrice,
            double unrealizedGain,
            double projectedStcgTax,
            double projectedLtcgTax,
            String status
    ) {}

    /** All lots for a single symbol with totals. */
    public record SymbolLotsResponse(
            String symbol,
            int totalOpenQuantity,
            double totalCostBasis,
            double totalUnrealizedGain,
            double totalProjectedTax,
            List<LotView> lots
    ) {}

    /** Request body to create a new lot manually. */
    public record CreateLotRequest(
            String symbol,
            int quantity,
            double buyPrice,
            LocalDate buyDate,
            double buyCharges,
            String notes,
            String tradeId,
            String source,
            String isin
    ) {
        public CreateLotRequest(String symbol, int quantity, double buyPrice,
                                LocalDate buyDate, double buyCharges, String notes) {
            this(symbol, quantity, buyPrice, buyDate, buyCharges, notes, null, null, null);
        }
        public CreateLotRequest(String symbol, int quantity, double buyPrice,
                                LocalDate buyDate, double buyCharges, String notes,
                                String tradeId, String source) {
            this(symbol, quantity, buyPrice, buyDate, buyCharges, notes, tradeId, source, null);
        }
    }

    /** Request body to record a sale — triggers lot matching. */
    public record RecordSaleRequest(
            String symbol,
            int quantity,
            double sellPrice,
            LocalDate sellDate,
            double sellCharges,
            String matchingMethod, // FIFO | LIFO | HIFO (null = config default)
            String tradeId
    ) {
        public RecordSaleRequest(String symbol, int quantity, double sellPrice,
                                 LocalDate sellDate, double sellCharges, String matchingMethod) {
            this(symbol, quantity, sellPrice, sellDate, sellCharges, matchingMethod, null);
        }
    }

    /** Response after a sale: matched lots + tax breakdown. */
    public record SaleResponse(
            String symbol,
            int quantitySold,
            double grossProceeds,
            double totalRealizedGain,
            double shortTermGain,
            double longTermGain,
            double stcgTax,
            double ltcgTax,
            String matchingMethod,
            List<MatchedSale> matches
    ) {}

    public record MatchedSale(
            Long lotId,
            int quantity,
            double buyPrice,
            LocalDate buyDate,
            int daysHeld,
            double realizedGain,
            String gainType
    ) {}

    /** A single harvest opportunity. */
    public record HarvestItem(
            Long lotId,
            String symbol,
            int remainingQuantity,
            double buyPrice,
            double currentPrice,
            LocalDate buyDate,
            int daysHeld,
            int daysToLtcgCutoff,
            double unrealizedGain,
            String reason // LOSS_HARVEST | APPROACHING_LTCG | LTCG_ELIGIBLE
    ) {}

    public record HarvestResponse(
            double fiscalYearRealizedStcg,
            double fiscalYearRealizedLtcg,
            double fiscalYearExemptionRemaining,
            List<HarvestItem> lossHarvestCandidates,
            List<HarvestItem> approachingLtcgCutoff,
            List<HarvestItem> ltcgEligible
    ) {}

    /**
     * Aggregate per-symbol holding-period split, used by the holdings email to gate
     * profit-booking suggestions on LTCG-eligible quantity (SPEC.md §9, §16).
     *
     * @param dataSource         {@code TAX_LOTS} (precise, lot-by-lot),
     *                           {@code HOLDINGS_PURCHASE_DATE} (approximation from the
     *                           single purchaseDate on HoldingsEntity), or {@code UNKNOWN}.
     * @param daysUntilNextLtcg  Smallest number of days remaining for any short-term qty
     *                           to graduate to LTCG; {@code null} when nothing is short-term.
     */
    public record TaxAwareExitClassification(
            String symbol,
            int totalQuantity,
            int ltcgEligibleQuantity,
            int stcgQuantity,
            Integer daysUntilNextLtcg,
            String dataSource
    ) {
        public boolean hasLtcgEligible() { return ltcgEligibleQuantity > 0; }
        public boolean fullyShortTerm() { return ltcgEligibleQuantity == 0 && stcgQuantity > 0; }
        public boolean hasMixedHorizon() { return ltcgEligibleQuantity > 0 && stcgQuantity > 0; }
    }
}
