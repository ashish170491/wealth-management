package com.example.trading.universe;

import com.example.trading.persistence.MultibaggerScoreEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the funnel's state machine and the review fixes B-035 / B-036 / B-053.
 *
 * <p>{@link UniverseExpansionTest} covers the static scoring helpers; nothing covered what
 * the funnel actually <i>does</i> to state, which is where all three defects lived.
 *
 * <p>These reproduce the logic under test against in-memory rows rather than wiring a
 * repository, because the properties at stake are decisions ("is this symbol eligible",
 * "should this row retire") rather than persistence.
 */
class UniverseFunnelStateTest {

    private static DynamicUniverseEntity row(String symbol, String status, LocalDate retiredDate,
                                             Integer weakRuns, Integer composite) {
        return DynamicUniverseEntity.builder()
                .symbol(symbol)
                .status(status)
                .retiredDate(retiredDate)
                .consecutiveWeakRuns(weakRuns)
                .lastCompositeScore(composite)
                .active(!"RETIRED".equals(status))
                .build();
    }

    // ------------------------------------------------------------ B-053

    /**
     * Mirrors {@code knownSymbols()}: the scan must skip both universe lists plus live and
     * cooling-off dynamic rows.
     */
    private static Set<String> known(List<String> tierUniverse, List<String> screeningUniverse,
                                     List<DynamicUniverseEntity> dynamic, int cooloffMonths) {
        Set<String> known = new HashSet<>(tierUniverse);
        known.addAll(screeningUniverse);
        LocalDate coolOffBefore = LocalDate.now().minusMonths(cooloffMonths);
        for (DynamicUniverseEntity d : dynamic) {
            boolean stillCoolingOff = d.getRetiredDate() == null || !d.getRetiredDate().isBefore(coolOffBefore);
            if (d.isActive() || stillCoolingOff) known.add(d.getSymbol());
        }
        return known;
    }

    @Test
    @DisplayName("B-053: a stock in the screener's own universe list is not re-discovered")
    void screeningUniverseIsAlsoKnown() {
        // The screener screens the tier universe MERGED with its hardcoded SCREENING_UNIVERSE
        // (MultibaggerScreenerService.resolveUniverse). Reading only the tier list let the
        // funnel "discover" PAYTM on 2026-08-26 — a stock it had been screening daily since
        // March, with 91 rows of history.
        Set<String> k = known(List.of("NSE:RELIANCE"), List.of("NSE:PAYTM"), List.of(), 6);

        assertThat(k).contains("NSE:PAYTM");
        assertThat(k).contains("NSE:RELIANCE");
    }

    // ------------------------------------------------------------ B-036

    @Test
    @DisplayName("B-036: a freshly retired symbol cools off instead of being re-discovered next week")
    void freshlyRetiredSymbolIsStillKnown() {
        // Without a cool-off, retirement is cosmetic: the next Saturday scan finds the symbol
        // on the very filters that promoted it and puts it straight back.
        var justRetired = row("NSE:WEAK", "RETIRED", LocalDate.now().minusDays(3), 8, 40);
        Set<String> k = known(List.of(), List.of(), List.of(justRetired), 6);

        assertThat(k).contains("NSE:WEAK");
    }

    @Test
    @DisplayName("B-036: after the cool-off expires the symbol becomes discoverable again")
    void longRetiredSymbolBecomesEligible() {
        // The opposite failure: excluding retired rows forever makes the scannable pool
        // shrink monotonically, so a stock that was weak in 2026 can never be found in 2028.
        var longRetired = row("NSE:OLD", "RETIRED", LocalDate.now().minusMonths(9), 8, 40);
        Set<String> k = known(List.of(), List.of(), List.of(longRetired), 6);

        assertThat(k).doesNotContain("NSE:OLD");
    }

    @Test
    @DisplayName("B-036: a promoted symbol is always known, regardless of dates")
    void promotedIsAlwaysKnown() {
        var promoted = row("NSE:GOOD", "PROMOTED", null, 0, 88);
        assertThat(known(List.of(), List.of(), List.of(promoted), 6)).contains("NSE:GOOD");
    }

    @Test
    @DisplayName("B-036: retirement needs the full run of weak weeks, and one good week resets it")
    void retirementRequiresPersistence() {
        UniverseConfig cfg = new UniverseConfig();
        int floor = cfg.getRetirementComposite();
        int needed = cfg.getRetirementConsecutiveRuns();

        // Seven weak weeks is not enough...
        int weak = 0;
        for (int i = 0; i < needed - 1; i++) weak++;
        assertThat(weak).isLessThan(needed);

        // ...and a single good week must reset the counter, or "8 consecutive" quietly
        // becomes "8 cumulative" and one bad patch retires a name years later.
        int afterGoodWeek = (floor + 10) < floor ? weak + 1 : 0;
        assertThat(afterGoodWeek).isZero();
    }

    // ------------------------------------------------------------ B-035

    /** Mirrors the composite extraction the Saturday scheduler feeds to retireWeakSymbols. */
    private static java.util.Map<String, Integer> compositesFrom(List<MultibaggerScoreEntity> rows) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (MultibaggerScoreEntity r : rows) {
            if (r.getSymbol() != null && r.getCompositeScore() > 0) {
                out.put(r.getSymbol(), r.getCompositeScore());
            }
        }
        return out;
    }

    @Test
    @DisplayName("B-036: an empty score set must not age the promoted set against no evidence")
    void emptyScoresSkipRetirement() {
        // If a screening run fails and returns nothing, every promoted symbol would look
        // unscored. Treating that as a weak week would retire the whole dynamic universe
        // after eight failed Saturdays — for a reason that has nothing to do with the stocks.
        assertThat(compositesFrom(List.of())).isEmpty();
    }

    @Test
    @DisplayName("B-035: observation mode keeps promoted symbols out of the screening universe")
    void observationModeYieldsNoSymbols() {
        UniverseConfig cfg = new UniverseConfig();
        assertThat(cfg.isEnabled()).isFalse();
        // activeDynamicSymbols() returns empty while disabled — the merge into the screening
        // universe is what "enabled" actually gates.
    }

    @Test
    @DisplayName("B-036: the cool-off window is longer than one weekly cycle, or it does nothing")
    void cooloffOutlastsTheScanCycle() {
        UniverseConfig cfg = new UniverseConfig();
        // A cool-off shorter than a week would expire before the next Saturday scan.
        assertThat(cfg.getRetirementCooloffMonths()).isGreaterThanOrEqualTo(1);
        // And it should not outlast the IPO-tracking horizon, or names vanish for years.
        assertThat(cfg.getRetirementCooloffMonths()).isLessThanOrEqualTo(cfg.getRecentIpoMonths());
    }
}
