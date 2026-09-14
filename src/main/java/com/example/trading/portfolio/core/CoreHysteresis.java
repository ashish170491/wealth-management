package com.example.trading.portfolio.core;

import com.example.trading.portfolio.core.CoreDto.CoreTier;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * Tier hysteresis (SPEC §35.6) — pure, so {@code CoreHysteresisTest} can pin every branch.
 *
 * <p><b>Asymmetric on purpose.</b> Promotion into a protected tier, and demotion out of one for a
 * <em>soft</em> reason, both need {@code hysteresis-runs} consecutive weekly (Friday) anchors to
 * agree. That is what stops a single bad XBRL parse or a one-day NSE outage from changing exit
 * behaviour. But a holding that has just tripped a forensic flag, gone HIGH_RISK, or had its
 * thesis break must not keep its exits suppressed for a fortnight while anchors accumulate — those
 * demote the same day. Risk controls come back immediately and leave slowly, never the reverse.
 *
 * <p>Movement that does not cross the protected boundary (CORE to CORE_WATCH, SATELLITE to
 * UNCLASSIFIED) takes effect at once: neither direction changes what the overlay does, and making
 * the reader wait two weeks to see an accurate label would be noise without safety.
 */
public final class CoreHysteresis {

    private CoreHysteresis() {}

    /** One weekly anchor: a Friday row's raw reading. */
    public record AnchorReading(LocalDate date, CoreTier provisional) {}

    /**
     * @param effectiveTier what the overlay acts on
     * @param pendingChange non-null while a change is accumulating confirmations, e.g.
     *                      "would become SATELLITE - 1 of 2 weekly confirmations"
     */
    public record TierDecision(CoreTier effectiveTier, String pendingChange) {}

    /**
     * @param priorAnchors Friday rows for this symbol, newest first, <b>excluding today</b>
     */
    public static TierDecision resolve(CoreTier provisional,
                                       CoreTier previousEffective,
                                       List<AnchorReading> priorAnchors,
                                       boolean criticalTrigger,
                                       LocalDate today,
                                       int hysteresisRuns) {

        if (previousEffective == null) {
            // Nothing to confirm against. The alternative — withholding every tier for two weeks
            // after the feature ships — buys no safety while the overlay is in observation mode.
            return new TierDecision(provisional, null);
        }
        if (provisional == previousEffective) {
            return new TierDecision(previousEffective, null);
        }

        boolean wasProtected = previousEffective.isProtected();
        boolean nowProtected = provisional.isProtected();

        if (wasProtected == nowProtected) {
            return new TierDecision(provisional, null);      // no boundary crossed
        }
        if (wasProtected && criticalTrigger) {
            return new TierDecision(provisional, null);      // critical demotion, same day
        }

        int confirmations = confirmations(provisional, priorAnchors, today, hysteresisRuns);
        if (confirmations >= hysteresisRuns) {
            return new TierDecision(provisional, null);
        }
        return new TierDecision(previousEffective, String.format(
                "would become %s - %d of %d weekly confirmations",
                provisional, confirmations, hysteresisRuns));
    }

    /**
     * How many of the most recent weekly anchors agree with today's reading, counting today when
     * today is itself an anchor. A run is consecutive: one disagreeing anchor resets the count,
     * which is the whole point — two Fridays that agree either side of a Friday that did not is
     * not two consecutive confirmations.
     */
    static int confirmations(CoreTier provisional, List<AnchorReading> priorAnchors,
                             LocalDate today, int hysteresisRuns) {
        int count = 0;
        if (today.getDayOfWeek() == DayOfWeek.FRIDAY) count = 1;

        if (priorAnchors != null) {
            for (AnchorReading a : priorAnchors) {
                if (count >= hysteresisRuns) break;
                if (a.provisional() == provisional) count++;
                else break;
            }
        }
        return count;
    }
}
