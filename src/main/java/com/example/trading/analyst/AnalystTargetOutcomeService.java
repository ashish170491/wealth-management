package com.example.trading.analyst;

import com.example.trading.learning.validation.DailyCandleCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Measures what happened after each recorded target (SPEC §49.5).
 *
 * <p><b>Reached is measured on the intraday extreme, not the close.</b> A target is reached the
 * day the price first touches it, whether or not it holds. Measuring on closes would silently
 * report "missed" for every call that got there and gave it back, which is a different claim
 * about the analyst.
 *
 * <p><b>Measurement starts the day after the call.</b> A target quoted at a level the stock had
 * already traded through that morning is not a forecast, and counting the call's own day would
 * hand a free hit to every note published intraday.
 *
 * <p><b>Two numbers, deliberately.</b> Whether the target was reached is the analyst's own
 * scoreboard — the kind of private board that always reads better than a neutral one (Gotcha 25).
 * Beside it sits the return over the Nifty 50 across exactly the same dates, which is what §23
 * measures this app's own picks on, so the two records are comparable.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalystTargetOutcomeService {

    /** The index every excess return here is taken against — the same one §23 uses. */
    static final String NIFTY = "NSE:NIFTY 50";

    /** Candles are warmed a little before the oldest call so a close on the issue date resolves. */
    private static final int WARM_LEAD_DAYS = 10;

    private final AnalystTargetRepository repository;
    private final DailyCandleCache candleCache;
    private final AnalystTargetConfig config;

    public record MeasurementResult(int examined,
                                    int priced,
                                    int stillUnpriced,
                                    int reached,
                                    int missed,
                                    int stillPending,
                                    boolean stoppedEarly,
                                    String note) {
    }

    /** Measure a bounded slice of the open book. */
    public MeasurementResult measureOpenTargets(BooleanSupplier shouldStop) {
        return measureOpenTargets(shouldStop, config.getMaxMeasurementsPerRun());
    }

    /**
     * The same pass with an explicit row budget (SPEC §49.11).
     *
     * <p>The scheduled run is capped at {@code max-measurements-per-run} because it shares the one
     * broker rate limit with the 14:00 screening. A one-off catch-up after an archive backfill has
     * no such contention out of hours, and needs a bigger budget or it would take months of daily
     * runs to price what a backfill writes in minutes.
     *
     * <p>Raising it is cheaper than it looks: the candle cache is warmed <b>per symbol</b>, so a
     * thousand targets across two hundred companies costs two hundred broker calls, not a
     * thousand. The cap counts rows because rows are what a deadline has to bound.
     */
    public MeasurementResult measureOpenTargets(BooleanSupplier shouldStop, int limit) {
        if (!config.isEnabled()) {
            return new MeasurementResult(0, 0, 0, 0, 0, 0, false, "Analyst target measurement is disabled.");
        }

        int budget = limit > 0 ? limit : config.getMaxMeasurementsPerRun();
        List<AnalystTargetEntity> open =
                repository.findOpenForMeasurement(PageRequest.of(0, budget));
        if (open.isEmpty()) {
            return new MeasurementResult(0, 0, 0, 0, 0, 0, false, "No open analyst targets to measure.");
        }

        // The deadline is checked BEFORE the cache is warmed, not only inside the loop below.
        // Warming is the expensive half - one paced broker call per distinct symbol - so a run
        // that starts already past its stop time used to fetch every candle and then break on
        // the first iteration, spending exactly the budget the deadline exists to protect and
        // measuring nothing. Observed 2026-09-17, when thread contention delayed the 13:20 job
        // to 13:51 against a 13:50 stop: "0 open targets examined ... Candles: 93 fetched".
        // Gotcha 97/101 - the reason for the guard is contention, so the guard has to cover the
        // contention rather than the moment of asking.
        if (shouldStop != null && shouldStop.getAsBoolean()) {
            log.info("Analyst target measurement skipped: already past its stop time before any "
                    + "broker call. {} open targets keep their place at the front of the next "
                    + "run's queue.", open.size());
            return new MeasurementResult(0, 0, 0, 0, 0, 0, true,
                    "Past the stop time before measuring began; no broker calls were made.");
        }

        LocalDate earliest = open.stream()
                .map(AnalystTargetEntity::getIssuedOn)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now())
                .minusDays(WARM_LEAD_DAYS);

        Set<String> symbols = new LinkedHashSet<>();
        for (AnalystTargetEntity t : open) symbols.add(t.getSymbol());
        symbols.add(NIFTY);

        DailyCandleCache.WarmResult warm = candleCache.warm(symbols, earliest);

        int priced = 0, unpriced = 0, reached = 0, missed = 0, pending = 0;
        boolean stopped = false;
        List<AnalystTargetEntity> dirty = new ArrayList<>();

        for (AnalystTargetEntity target : open) {
            if (shouldStop != null && shouldStop.getAsBoolean()) {
                stopped = true;
                log.info("Analyst target measurement stopped early at its deadline after {} rows; "
                        + "the rest keep their place at the front of the next run's queue.", dirty.size());
                break;
            }
            try {
                measure(target);
                dirty.add(target);
                switch (AnalystTargetStatus.valueOf(target.getStatus())) {
                    case REACHED -> { reached++; priced++; }
                    case MISSED -> { missed++; priced++; }
                    case PENDING -> { pending++; priced++; }
                    case UNPRICED -> unpriced++;
                    default -> { }
                }
            } catch (Exception e) {
                log.debug("Analyst ledger: could not measure target {} ({}): {}",
                        target.getId(), target.getSymbol(), e.getMessage());
            }
        }

        try {
            repository.saveAll(dirty);
        } catch (Exception e) {
            log.warn("Analyst ledger: {} measured rows could not be saved ({}). Their status on screen "
                    + "is one run stale, not wrong.", dirty.size(), e.getMessage());
        }

        String note = String.format("%d open targets examined: %d reached, %d missed, %d still running, "
                        + "%d still unpriced. Candles: %d fetched, %d reused, %d unavailable.",
                dirty.size(), reached, missed, pending, unpriced,
                warm.fetched(), warm.reused(), warm.failed());
        log.info("Analyst target measurement: {}", note);
        return new MeasurementResult(dirty.size(), priced, unpriced, reached, missed, pending, stopped, note);
    }

    /** Measure one row in place. Does not save. */
    void measure(AnalystTargetEntity t) {
        LocalDate issued = t.getIssuedOn();
        LocalDate today = LocalDate.now();
        // A resolved call is measured at its horizon, not at today: reading a 12-month call's
        // return 18 months later is measuring a different holding period from the one claimed.
        LocalDate asOf = t.getResolvesOn().isBefore(today) ? t.getResolvesOn() : today;

        t.setLastMeasuredAt(LocalDateTime.now());

        Double atCall = t.getPriceAtCall() != null
                ? t.getPriceAtCall()
                : candleCache.closeOnOrBefore(t.getSymbol(), issued);
        if (atCall == null || atCall <= 0) {
            // No reference price means no upside, no direction and no return. It stays UNPRICED
            // and stays out of every hit rate, rather than acquiring a zero that would read as a
            // measured result (SPEC §21 rule 7).
            t.setStatus(AnalystTargetStatus.UNPRICED.name());
            return;
        }

        t.setPriceAtCall(atCall);
        boolean upward = t.getTargetPrice() >= atCall;
        t.setDirection(upward ? "ABOVE" : "BELOW");
        t.setUpsidePctAtCall((t.getTargetPrice() - atCall) / atCall * 100.0);

        if (t.getNiftyAtCall() == null) {
            t.setNiftyAtCall(candleCache.closeOnOrBefore(NIFTY, issued));
        }

        LocalDate from = issued.plusDays(1);
        if (!from.isAfter(asOf)) {
            DailyCandleCache.Extreme crossing =
                    candleCache.firstCrossing(t.getSymbol(), from, asOf, t.getTargetPrice(), upward);
            if (crossing != null) {
                t.setReachedOn(crossing.date());
                t.setDaysToReach((int) ChronoUnit.DAYS.between(issued, crossing.date()));
            }
            DailyCandleCache.Extreme best = candleCache.extremeBetween(t.getSymbol(), from, asOf, upward);
            if (best != null) {
                double move = (best.value() - atCall) / atCall * 100.0;
                t.setMaxFavourablePct(upward ? move : -move);
            }
        }

        Double now = candleCache.closeOnOrBefore(t.getSymbol(), asOf);
        if (now != null && now > 0) {
            t.setLastPrice(now);
            t.setReturnPct((now - atCall) / atCall * 100.0);
            Double niftyNow = candleCache.closeOnOrBefore(NIFTY, asOf);
            if (niftyNow != null && t.getNiftyAtCall() != null && t.getNiftyAtCall() > 0) {
                double niftyReturn = (niftyNow - t.getNiftyAtCall()) / t.getNiftyAtCall() * 100.0;
                t.setNiftyReturnPct(niftyReturn);
                t.setExcessReturnPct(t.getReturnPct() - niftyReturn);
            }
        }

        if (t.getReachedOn() != null) {
            t.setStatus(AnalystTargetStatus.REACHED.name());
        } else if (today.isAfter(t.getResolvesOn())) {
            t.setStatus(AnalystTargetStatus.MISSED.name());
        } else {
            t.setStatus(AnalystTargetStatus.PENDING.name());
        }
    }
}
