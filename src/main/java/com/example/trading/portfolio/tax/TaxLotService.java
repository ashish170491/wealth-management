package com.example.trading.portfolio.tax;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Core service for Tax-Lot Tracking (SPEC.md §9).
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Persist purchases as discrete {@link TaxLotEntity} records.</li>
 *   <li>Match sales against lots (FIFO/LIFO/HIFO) producing {@link TaxLotSaleEntity} rows
 *       with realized STCG/LTCG classification.</li>
 *   <li>Project tax on open lots vs today's price for each holding.</li>
 *   <li>Surface harvest opportunities — loss lots, lots approaching LTCG cutoff, lots already LTCG-eligible.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxLotService {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String GAIN_LONG_TERM = "LONG_TERM";
    public static final String GAIN_SHORT_TERM = "SHORT_TERM";

    private final TaxLotRepository lotRepository;
    private final TaxLotSaleRepository saleRepository;
    private final HoldingsRepository holdingsRepository;
    private final TaxConfig taxConfig;

    /**
     * Remove ALL tax lots and sales — used to clear synthetic/test data before a real import.
     * Returns the number of rows deleted. This is destructive; see
     * {@code DELETE /api/portfolio/tax-lots/all} in the controller.
     */
    @Transactional
    public java.util.Map<String, Long> deleteAll() {
        long salesDeleted = saleRepository.count();
        saleRepository.deleteAllInBatch();
        long lotsDeleted = lotRepository.count();
        lotRepository.deleteAllInBatch();
        log.warn("TaxLot: deleted {} lots and {} sales (full wipe)", lotsDeleted, salesDeleted);
        return java.util.Map.of("lotsDeleted", lotsDeleted, "salesDeleted", salesDeleted);
    }

    /**
     * Bulk-import lots from a list of requests — used for one-shot seeding from a
     * Zerodha tradebook export. Each request is processed in sequence.
     */
    @Transactional
    public java.util.Map<String, Object> bulkImport(java.util.List<TaxLotDto.CreateLotRequest> requests) {
        int created = 0;
        int failed = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (TaxLotDto.CreateLotRequest req : requests) {
            try {
                createLot(req);
                created++;
            } catch (Exception e) {
                failed++;
                errors.add(req.symbol() + " " + req.buyDate() + ": " + e.getMessage());
            }
        }
        log.info("TaxLot: bulk import done — {} created, {} failed", created, failed);
        return java.util.Map.of(
                "created", created,
                "failed", failed,
                "errors", errors);
    }

    @Transactional
    public TaxLotEntity createLot(TaxLotDto.CreateLotRequest req) {
        // Idempotency: a non-null tradeId means this lot is sourced from a real broker trade
        // (Zerodha CSV or live auto-capture). Re-running the importer or scheduler should
        // not duplicate rows, so short-circuit when we already have one for that tradeId.
        if (req.tradeId() != null && !req.tradeId().isBlank()) {
            java.util.Optional<TaxLotEntity> existingOpt = lotRepository.findByTradeId(req.tradeId());
            if (existingOpt.isPresent()) {
                TaxLotEntity existing = existingOpt.get();
                // Backfill ISIN on a previously imported lot if the original import (before
                // ISIN was tracked) didn't set it. Lets users re-upload tradebook CSVs to
                // gain cross-exchange aggregation without first wiping the ledger.
                if ((existing.getIsin() == null || existing.getIsin().isBlank())
                        && req.isin() != null && !req.isin().isBlank()) {
                    existing.setIsin(req.isin());
                    lotRepository.save(existing);
                    log.info("TaxLot: backfilled ISIN {} on existing lot {} (tradeId {})",
                            req.isin(), existing.getId(), req.tradeId());
                }
                log.debug("TaxLot: skipping duplicate import — tradeId {} already on lot {}",
                        req.tradeId(), existing.getId());
                return existing;
            }
        }
        String source = (req.source() != null && !req.source().isBlank()) ? req.source() : "MANUAL";
        TaxLotEntity lot = TaxLotEntity.builder()
                .symbol(req.symbol())
                .originalQuantity(req.quantity())
                .remainingQuantity(req.quantity())
                .buyPrice(req.buyPrice())
                .buyDate(req.buyDate())
                .buyCharges(req.buyCharges())
                .status(STATUS_OPEN)
                .source(source)
                .notes(req.notes())
                .tradeId(req.tradeId())
                .isin(req.isin())
                .build();
        lot = lotRepository.save(lot);
        log.info("TaxLot: created lot {} {} qty={} @ ₹{} on {} (source={}, tradeId={})",
                lot.getId(), lot.getSymbol(), lot.getOriginalQuantity(),
                lot.getBuyPrice(), lot.getBuyDate(), source, req.tradeId());
        return lot;
    }

    public TaxLotDto.SymbolLotsResponse getLotsForSymbol(String symbol) {
        List<TaxLotEntity> lots = lotRepository.findBySymbol(symbol);
        double currentPrice = currentPriceFor(symbol);
        LocalDate today = LocalDate.now();

        List<TaxLotDto.LotView> views = lots.stream()
                .map(l -> toLotView(l, currentPrice, today))
                .toList();

        int totalOpenQty = views.stream().mapToInt(TaxLotDto.LotView::remainingQuantity).sum();
        double totalCost = lots.stream()
                .filter(l -> STATUS_OPEN.equals(l.getStatus()))
                .mapToDouble(l -> l.getBuyPrice() * l.getRemainingQuantity()).sum();
        double totalUnrealized = views.stream()
                .filter(v -> STATUS_OPEN.equals(v.status()))
                .mapToDouble(TaxLotDto.LotView::unrealizedGain).sum();
        double totalProjectedTax = views.stream()
                .filter(v -> STATUS_OPEN.equals(v.status()))
                .mapToDouble(v -> v.projectedStcgTax() + v.projectedLtcgTax()).sum();

        return new TaxLotDto.SymbolLotsResponse(
                symbol, totalOpenQty, totalCost, totalUnrealized, totalProjectedTax, views);
    }

    @Transactional
    public TaxLotDto.SaleResponse recordSale(TaxLotDto.RecordSaleRequest req) {
        // Idempotency for CSV import / live capture: a non-null tradeId means we've already
        // processed this sell trade if any sale row carries the same tradeId.
        if (req.tradeId() != null && !req.tradeId().isBlank()) {
            List<TaxLotSaleEntity> existing = saleRepository.findByTradeId(req.tradeId());
            if (!existing.isEmpty()) {
                log.debug("TaxLot: skipping duplicate sale — tradeId {} already produced {} sale row(s)",
                        req.tradeId(), existing.size());
                return new TaxLotDto.SaleResponse(
                        req.symbol(), 0, 0, 0, 0, 0, 0, 0,
                        req.matchingMethod() != null ? req.matchingMethod() : taxConfig.getDefaultMatchingMethod(),
                        java.util.Collections.emptyList());
            }
        }

        String method = (req.matchingMethod() != null && !req.matchingMethod().isBlank())
                ? req.matchingMethod().toUpperCase() : taxConfig.getDefaultMatchingMethod().toUpperCase();

        List<TaxLotEntity> openLots = switch (method) {
            case "LIFO" -> lotRepository.findOpenBySymbolLifo(req.symbol());
            case "HIFO" -> lotRepository.findOpenBySymbolHifo(req.symbol());
            default -> lotRepository.findOpenBySymbolFifo(req.symbol());
        };

        int remainingToSell = req.quantity();
        int totalOpenQty = openLots.stream().mapToInt(TaxLotEntity::getRemainingQuantity).sum();
        if (remainingToSell > totalOpenQty) {
            throw new IllegalArgumentException(String.format(
                    "Cannot sell %d %s: only %d open quantity available",
                    remainingToSell, req.symbol(), totalOpenQty));
        }

        List<TaxLotDto.MatchedSale> matches = new ArrayList<>();
        double totalGain = 0, stGain = 0, ltGain = 0;

        // Allocate sell charges proportionally across matched quantities.
        double chargesPerShare = req.sellCharges() / (double) req.quantity();

        for (TaxLotEntity lot : openLots) {
            if (remainingToSell <= 0) break;
            int qty = Math.min(remainingToSell, lot.getRemainingQuantity());
            int daysHeld = (int) ChronoUnit.DAYS.between(lot.getBuyDate(), req.sellDate());
            String gainType = daysHeld > taxConfig.getLtcgCutoffDays() ? GAIN_LONG_TERM : GAIN_SHORT_TERM;
            double buyCostShare = lot.getBuyCharges() * (qty / (double) lot.getOriginalQuantity());
            double sellCostShare = chargesPerShare * qty;
            double gain = (req.sellPrice() - lot.getBuyPrice()) * qty - buyCostShare - sellCostShare;

            saleRepository.save(TaxLotSaleEntity.builder()
                    .lotId(lot.getId())
                    .symbol(req.symbol())
                    .quantity(qty)
                    .sellPrice(req.sellPrice())
                    .sellDate(req.sellDate())
                    .sellCharges(sellCostShare)
                    .daysHeld(daysHeld)
                    .realizedGain(gain)
                    .gainType(gainType)
                    .tradeId(req.tradeId())
                    .build());

            lot.setRemainingQuantity(lot.getRemainingQuantity() - qty);
            if (lot.getRemainingQuantity() == 0) lot.setStatus(STATUS_CLOSED);
            lotRepository.save(lot);

            matches.add(new TaxLotDto.MatchedSale(
                    lot.getId(), qty, lot.getBuyPrice(), lot.getBuyDate(), daysHeld, gain, gainType));
            remainingToSell -= qty;
            totalGain += gain;
            if (GAIN_LONG_TERM.equals(gainType)) ltGain += gain; else stGain += gain;
        }

        double stcgTax = Math.max(0, stGain) * taxConfig.getStcgRatePercent() / 100.0;
        double ltcgTax = computeLtcgTax(ltGain);

        log.info("TaxLot: sold {} {} via {}: realized ₹{} (ST ₹{}, LT ₹{})",
                req.quantity(), req.symbol(), method, totalGain, stGain, ltGain);

        return new TaxLotDto.SaleResponse(
                req.symbol(), req.quantity(), req.sellPrice() * req.quantity(),
                totalGain, stGain, ltGain, stcgTax, ltcgTax, method, matches);
    }

    /**
     * Classify a holding's open quantity into LTCG-eligible vs STCG buckets. Used by the
     * holdings email to gate "Book Profit" suggestions on long-term lots (avoiding STCG)
     * and to label sell-signal exits with the tax horizon being realized. SPEC.md §9.
     *
     * <p>Resolution order:
     * <ol>
     *   <li><strong>ISIN match</strong> — when the holding has an ISIN, look up open lots
     *       with the same ISIN regardless of exchange prefix. This handles the common
     *       scenario where the broker shows the holding under one exchange but lots were
     *       bought on the other (e.g. {@code BSE:WAAREEENER} holding ↔ {@code NSE:WAAREEENER} lots).</li>
     *   <li><strong>Symbol match (fallback)</strong> — exact {@code exchange:symbol} match
     *       when no ISIN-keyed lots exist (legacy lots imported before the ISIN column was added).</li>
     *   <li>Otherwise fall back to the single {@code purchaseDate} on the holdings record
     *       (approximation — assumes one buy event).</li>
     *   <li>If neither is available, return {@code UNKNOWN} with both buckets at 0.</li>
     * </ol>
     */
    public TaxLotDto.TaxAwareExitClassification classifyForExit(
            String symbol, String isin, int holdingsQty, LocalDate purchaseDate) {
        LocalDate today = LocalDate.now();
        int cutoff = taxConfig.getLtcgCutoffDays();

        List<TaxLotEntity> openLots = java.util.Collections.emptyList();
        if (isin != null && !isin.isBlank()) {
            openLots = lotRepository.findByIsinAndStatus(isin, STATUS_OPEN);
        }
        if (openLots.isEmpty()) {
            openLots = lotRepository.findBySymbolAndStatus(symbol, STATUS_OPEN);
        }

        if (!openLots.isEmpty()) {
            int ltcgQty = 0;
            int stcgQty = 0;
            Integer minDaysToCutoff = null;
            for (TaxLotEntity lot : openLots) {
                int daysHeld = (int) ChronoUnit.DAYS.between(lot.getBuyDate(), today);
                if (daysHeld > cutoff) {
                    ltcgQty += lot.getRemainingQuantity();
                } else {
                    stcgQty += lot.getRemainingQuantity();
                    int daysToCutoff = cutoff - daysHeld + 1;
                    if (minDaysToCutoff == null || daysToCutoff < minDaysToCutoff) {
                        minDaysToCutoff = daysToCutoff;
                    }
                }
            }
            return new TaxLotDto.TaxAwareExitClassification(
                    symbol, ltcgQty + stcgQty, ltcgQty, stcgQty, minDaysToCutoff, "TAX_LOTS");
        }

        if (purchaseDate != null && holdingsQty > 0) {
            int daysHeld = (int) ChronoUnit.DAYS.between(purchaseDate, today);
            if (daysHeld > cutoff) {
                return new TaxLotDto.TaxAwareExitClassification(
                        symbol, holdingsQty, holdingsQty, 0, null, "HOLDINGS_PURCHASE_DATE");
            }
            int daysToCutoff = cutoff - daysHeld + 1;
            return new TaxLotDto.TaxAwareExitClassification(
                    symbol, holdingsQty, 0, holdingsQty, daysToCutoff, "HOLDINGS_PURCHASE_DATE");
        }

        return new TaxLotDto.TaxAwareExitClassification(
                symbol, Math.max(0, holdingsQty), 0, 0, null, "UNKNOWN");
    }

    /**
     * How long a holding has been held, and from what evidence (SPEC §46.5).
     *
     * @param daysHeld   days since the EARLIEST open lot's buy date - the oldest money in the
     *                   position, which is what "how long have I owned this" means to an investor;
     *                   null when nothing on file can say
     * @param source     {@code TAX_LOTS} / {@code HOLDINGS_PURCHASE_DATE} / {@code UNKNOWN}
     */
    public record HoldingPeriod(Integer daysHeld, LocalDate firstBuyDate, String source,
                                int ltcgEligibleQuantity, int stcgQuantity, Integer daysUntilNextLtcg) {
        public static HoldingPeriod unknown() {
            return new HoldingPeriod(null, null, "UNKNOWN", 0, 0, null);
        }
    }

    /**
     * Lots by ISIN first (a BSE-held position matches its NSE lots), then by symbol across
     * exchange prefixes, then the single purchase date on the holding, then unknown. Unknown is
     * null days, never zero: "0 days held" is a purchase made today (Gotcha 21).
     */
    public HoldingPeriod holdingPeriod(String symbol, String isin, int holdingsQty, LocalDate purchaseDate) {
        LocalDate today = LocalDate.now();
        List<TaxLotEntity> openLots = java.util.Collections.emptyList();
        if (isin != null && !isin.isBlank()) {
            openLots = lotRepository.findByIsinAndStatus(isin, STATUS_OPEN);
        }
        if (openLots.isEmpty() && symbol != null) {
            for (String candidate : com.example.trading.holdings.SymbolVariants.candidates(symbol)) {
                openLots = lotRepository.findBySymbolAndStatus(candidate, STATUS_OPEN);
                if (!openLots.isEmpty()) break;
            }
        }
        if (!openLots.isEmpty()) {
            LocalDate earliest = openLots.stream().map(TaxLotEntity::getBuyDate)
                    .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
            TaxLotDto.TaxAwareExitClassification c = classifyForExit(symbol, isin, holdingsQty, purchaseDate);
            Integer days = earliest == null ? null : (int) ChronoUnit.DAYS.between(earliest, today);
            return new HoldingPeriod(days, earliest, "TAX_LOTS", c.ltcgEligibleQuantity(), c.stcgQuantity(),
                    c.daysUntilNextLtcg());
        }
        if (purchaseDate != null && holdingsQty > 0) {
            TaxLotDto.TaxAwareExitClassification c = classifyForExit(symbol, isin, holdingsQty, purchaseDate);
            return new HoldingPeriod((int) ChronoUnit.DAYS.between(purchaseDate, today), purchaseDate,
                    "HOLDINGS_PURCHASE_DATE", c.ltcgEligibleQuantity(), c.stcgQuantity(), c.daysUntilNextLtcg());
        }
        return HoldingPeriod.unknown();
    }

    /** Backwards-compatible 3-arg overload — defaults ISIN to null (symbol-only lookup). */
    public TaxLotDto.TaxAwareExitClassification classifyForExit(
            String symbol, int holdingsQty, LocalDate purchaseDate) {
        return classifyForExit(symbol, null, holdingsQty, purchaseDate);
    }

    public TaxLotDto.HarvestResponse computeHarvestSuggestions() {
        LocalDate today = LocalDate.now();
        LocalDate fyStart = fiscalYearStart(today);
        LocalDate fyEnd = fyStart.plusYears(1).minusDays(1);

        List<TaxLotSaleEntity> fySales = saleRepository.findBySellDateBetween(fyStart, fyEnd);
        double fyStcg = fySales.stream()
                .filter(s -> GAIN_SHORT_TERM.equals(s.getGainType()))
                .mapToDouble(TaxLotSaleEntity::getRealizedGain).sum();
        double fyLtcg = fySales.stream()
                .filter(s -> GAIN_LONG_TERM.equals(s.getGainType()))
                .mapToDouble(TaxLotSaleEntity::getRealizedGain).sum();
        double exemptionRemaining = Math.max(0, taxConfig.getLtcgExemptionPerYear() - Math.max(0, fyLtcg));

        List<TaxLotEntity> openLots = lotRepository.findByStatus(STATUS_OPEN);
        Map<String, Double> prices = openLots.stream()
                .map(TaxLotEntity::getSymbol)
                .distinct()
                .collect(Collectors.toMap(s -> s, this::currentPriceFor));

        List<TaxLotDto.HarvestItem> losses = new ArrayList<>();
        List<TaxLotDto.HarvestItem> approachingLt = new ArrayList<>();
        List<TaxLotDto.HarvestItem> ltEligible = new ArrayList<>();

        for (TaxLotEntity lot : openLots) {
            double price = prices.getOrDefault(lot.getSymbol(), 0.0);
            if (price <= 0) continue; // skip lots with no current price — can't harvest what we can't value
            int daysHeld = (int) ChronoUnit.DAYS.between(lot.getBuyDate(), today);
            int daysToCutoff = taxConfig.getLtcgCutoffDays() - daysHeld;
            double unrealized = (price - lot.getBuyPrice()) * lot.getRemainingQuantity();

            if (unrealized < 0) {
                losses.add(harvestItem(lot, price, daysHeld, daysToCutoff, unrealized, "LOSS_HARVEST"));
            }
            if (daysToCutoff > 0 && daysToCutoff <= taxConfig.getApproachingCutoffWindowDays() && unrealized > 0) {
                approachingLt.add(harvestItem(lot, price, daysHeld, daysToCutoff, unrealized, "APPROACHING_LTCG"));
            }
            if (daysToCutoff <= 0 && unrealized > 0) {
                ltEligible.add(harvestItem(lot, price, daysHeld, daysToCutoff, unrealized, "LTCG_ELIGIBLE"));
            }
        }

        return new TaxLotDto.HarvestResponse(fyStcg, fyLtcg, exemptionRemaining, losses, approachingLt, ltEligible);
    }

    private TaxLotDto.LotView toLotView(TaxLotEntity lot, double currentPrice, LocalDate today) {
        int daysHeld = (int) ChronoUnit.DAYS.between(lot.getBuyDate(), today);
        int daysToCutoff = Math.max(0, taxConfig.getLtcgCutoffDays() - daysHeld);
        String gainTypeIfSoldToday = daysHeld > taxConfig.getLtcgCutoffDays() ? GAIN_LONG_TERM : GAIN_SHORT_TERM;
        boolean priceAvailable = currentPrice > 0;
        double unrealized = (STATUS_OPEN.equals(lot.getStatus()) && priceAvailable)
                ? (currentPrice - lot.getBuyPrice()) * lot.getRemainingQuantity() : 0.0;
        double projectedStcg = 0, projectedLtcg = 0;
        if (unrealized > 0 && STATUS_OPEN.equals(lot.getStatus())) {
            if (GAIN_SHORT_TERM.equals(gainTypeIfSoldToday)) {
                projectedStcg = unrealized * taxConfig.getStcgRatePercent() / 100.0;
            } else {
                projectedLtcg = unrealized * taxConfig.getLtcgRatePercent() / 100.0;
            }
        }
        return new TaxLotDto.LotView(
                lot.getId(), lot.getSymbol(), lot.getOriginalQuantity(), lot.getRemainingQuantity(),
                lot.getBuyPrice(), lot.getBuyDate(), daysHeld, daysToCutoff,
                gainTypeIfSoldToday, currentPrice, unrealized, projectedStcg, projectedLtcg, lot.getStatus());
    }

    private TaxLotDto.HarvestItem harvestItem(TaxLotEntity lot, double price,
                                              int daysHeld, int daysToCutoff,
                                              double unrealized, String reason) {
        return new TaxLotDto.HarvestItem(
                lot.getId(), lot.getSymbol(), lot.getRemainingQuantity(),
                lot.getBuyPrice(), price, lot.getBuyDate(), daysHeld, Math.max(0, daysToCutoff),
                unrealized, reason);
    }

    private double computeLtcgTax(double longTermGain) {
        if (longTermGain <= 0) return 0;
        double taxable = Math.max(0, longTermGain - taxConfig.getLtcgExemptionPerYear());
        return taxable * taxConfig.getLtcgRatePercent() / 100.0;
    }

    private double currentPriceFor(String symbol) {
        return holdingsRepository.findBySymbol(symbol)
                .map(HoldingsEntity::getCurrentPrice)
                .filter(p -> p != null && p > 0)
                .orElse(0.0);
    }

    /** India fiscal year runs April 1 → March 31. */
    private LocalDate fiscalYearStart(LocalDate today) {
        return today.getMonth().getValue() >= Month.APRIL.getValue()
                ? LocalDate.of(today.getYear(), Month.APRIL, 1)
                : LocalDate.of(today.getYear() - 1, Month.APRIL, 1);
    }
}
