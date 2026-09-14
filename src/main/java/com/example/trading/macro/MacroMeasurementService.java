package com.example.trading.macro;

import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.intelligence.recommendation.RecommendationTracker;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.persistence.RecommendationEntity;
import com.example.trading.watchlist.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records what the exposure read said, so it can be checked later (SPEC §48.7).
 *
 * <p><b>Why this exists at all.</b> The two news features this app used to have were deleted in
 * September 2026 for the same reason: neither was ever a {@code RecommendationTracker} source, so
 * after months of running there was no hit rate, no excess return and no information coefficient
 * for either of them, and no way to tell whether they had ever been right (SPEC §39.3 rule 1). A
 * feature that scores itself is not measured, and a feature that is not measured cannot be
 * defended. Every directional reading this feature produces is therefore filed as a MACRO_EVENT
 * row and measured at 30, 90, 180 and 365 days by the machinery that already exists.
 *
 * <p><b>These rows are measurements, not picks.</b> A MACRO_EVENT row does not say a stock is worth
 * owning; it says the app read it as facing a headwind or a tailwind on a given day. The verdict is
 * stored so accuracy can be scored directionally - a headwind followed by a fall is a <i>correct</i>
 * reading - and every surface that shows the investor "the app's picks" filters this source out.
 *
 * <p><b>One index quote per ingest, not one per stock.</b> The tracker ordinarily fetches the Nifty
 * level per row, which is fine for an engine recording a few picks and ruinous for one recording
 * three hundred: the broker is paced process-wide at about 2.9 requests a second, and that budget
 * has twice starved the afternoon schedulers into aborting silently at 15:30 (B-014, B-049).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroMeasurementService {

    /** Filed against a tailwind reading. Nothing consults it; it exists so IC has a number to correlate. */
    static final int TAILWIND_SCORE = 75;
    /** Filed against a headwind reading. */
    static final int HEADWIND_SCORE = 25;

    private final MacroExposureService exposureService;
    private final RecommendationTracker tracker;
    private final HoldingsRepository holdingsRepository;
    private final WatchlistRepository watchlistRepository;
    private final MultibaggerScreenerService screenerService;
    private final MultibaggerScoreRepository scoreRepository;

    /**
     * @param recorded          directional readings filed for measurement
     * @param skippedNoPrice    stocks whose price could not be established, so a return could never
     *                          be computed for them; counted rather than silently dropped
     * @param notDirectional    stocks read as mixed, not exposed or not measured - nothing to score
     */
    public record MeasurementResult(int considered, int recorded, int skippedNoPrice, int notDirectional) {
    }

    /**
     * File every directional reading in the tracked universe.
     *
     * <p>Called after an ingest writes new events. Readings that are MIXED, NOT_EXPOSED or
     * NOT_MEASURED are deliberately not recorded: there is no directional claim in them to be right
     * or wrong about, and filing them would dilute the measurement with rows that cannot fail.
     */
    public MeasurementResult recordExposures() {
        Set<String> universe = trackedUniverse();
        if (universe.isEmpty()) {
            return new MeasurementResult(0, 0, 0, 0);
        }

        Map<String, MacroExposureService.Reading> readings = exposureService.forSymbols(universe);
        Map<String, Double> prices = latestPrices(universe);
        Double nifty = tracker.currentNiftyLevel();

        int recorded = 0;
        int skippedNoPrice = 0;
        int notDirectional = 0;

        for (Map.Entry<String, MacroExposureService.Reading> e : readings.entrySet()) {
            MacroExposureRead.Result result = e.getValue().result();
            boolean tailwind = result.verdict() == MacroExposureRead.Verdict.TAILWIND;
            boolean headwind = result.verdict() == MacroExposureRead.Verdict.HEADWIND;
            if (!tailwind && !headwind) {
                notDirectional++;
                continue;
            }

            Double price = prices.get(e.getKey());
            if (price == null || price <= 0) {
                // Without a price at issue there is no return to measure later, so recording the
                // row would create a measurement that can never mature. Counted so the ingest
                // response can say how much of the universe went unmeasured.
                skippedNoPrice++;
                continue;
            }

            RecommendationEntity saved = tracker.record(
                    RecommendationEntity.Source.MACRO_EVENT,
                    e.getKey(),
                    price,
                    tailwind ? TAILWIND_SCORE : HEADWIND_SCORE,
                    null,
                    result.verdict().name(),
                    null,
                    null,
                    e.getValue().sector(),
                    null,
                    factorDimensions(result),
                    nifty);
            if (saved != null) recorded++;
        }

        log.info("Macro measurement: {} stocks considered, {} directional readings filed, "
                        + "{} skipped for want of a price, {} not directional",
                readings.size(), recorded, skippedNoPrice, notDirectional);
        return new MeasurementResult(readings.size(), recorded, skippedNoPrice, notDirectional);
    }

    /**
     * Per-factor sub-scores, signed by effect and sized by strength.
     *
     * <p>This is what makes "which macro factor actually predicted anything" answerable a year from
     * now, through the same per-dimension information-coefficient panel every other engine uses. A
     * factor that never predicts anything will read near zero and can be argued out of the map on
     * evidence, which is the only honest route a signal has in this codebase (SPEC §38.10).
     */
    static Map<String, Integer> factorDimensions(MacroExposureRead.Result result) {
        Map<String, Integer> dims = new LinkedHashMap<>();
        for (MacroExposureRead.Reason r : result.reasons()) {
            int size = switch (r.strength()) {
                case HIGH -> 3;
                case MEDIUM -> 2;
                case LOW -> 1;
            };
            int signed = switch (r.effect()) {
                case TAILWIND -> size;
                case HEADWIND -> -size;
                case MIXED -> 0;
            };
            dims.merge(r.factor().name(), signed, Integer::sum);
        }
        return dims;
    }

    /**
     * Everything the investor actually follows: what they own, what they watch, and the screening
     * universe.
     *
     * <p>Uses the resolved universe rather than the legacy hard-coded list, which covers only about
     * a quarter of what is screened (Gotcha 103).
     */
    private Set<String> trackedUniverse() {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (HoldingsEntity h : holdingsRepository.findActive()) {
                if (h.getSymbol() != null) out.add(h.getSymbol());
            }
        } catch (Exception e) {
            log.warn("Macro measurement: holdings unavailable ({}), so readings on owned stocks are "
                    + "not being filed for measurement.", e.getMessage());
        }
        try {
            watchlistRepository.findAll().stream()
                    .map(w -> w.getSymbol())
                    .filter(s -> s != null && !s.isBlank())
                    .forEach(out::add);
        } catch (Exception e) {
            log.warn("Macro measurement: watchlist unavailable ({}).", e.getMessage());
        }
        try {
            out.addAll(screenerService.resolvedScreeningUniverse());
        } catch (Exception e) {
            log.warn("Macro measurement: screening universe unavailable ({}), so measurement covers "
                    + "only holdings and the watchlist.", e.getMessage());
        }
        return out;
    }

    /**
     * A price per symbol from what is already stored - holdings first, then the newest screening
     * row. Deliberately no broker call: this runs inside an ingest the investor is waiting on, and
     * a day-old close is entirely adequate as the basis for a 180-day return.
     */
    private Map<String, Double> latestPrices(Set<String> universe) {
        Map<String, Double> prices = new LinkedHashMap<>();
        try {
            for (HoldingsEntity h : holdingsRepository.findActive()) {
                if (h.getSymbol() != null && h.getCurrentPrice() > 0) {
                    prices.put(h.getSymbol(), h.getCurrentPrice());
                }
            }
        } catch (Exception e) {
            log.debug("Macro measurement: holdings prices unavailable: {}", e.getMessage());
        }
        try {
            List<String> missing = new ArrayList<>();
            for (String s : universe) {
                if (!prices.containsKey(s)) missing.add(s);
            }
            if (!missing.isEmpty()) {
                for (MultibaggerScoreEntity row : scoreRepository.findRecentForSymbols(
                        missing, LocalDate.now().minusDays(30))) {
                    if (row.getCurrentPrice() > 0) {
                        prices.put(row.getSymbol(), row.getCurrentPrice());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Macro measurement: screening prices unavailable ({}), so most readings will be "
                    + "skipped for want of a price and will never be measured.", e.getMessage());
        }
        return prices;
    }
}
