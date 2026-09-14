package com.example.trading.analyst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract and the vocabulary of the analyst ledger (SPEC §49.9).
 *
 * <p><b>Why a test for field names.</b> There is no build step for {@code analyst-cells.js} and
 * nothing type-checks the wire, so a rename or a typo does not fail anywhere: the cell finds
 * {@code undefined} and draws "not measured" for ever, on a target the app measured perfectly
 * well. That is the quietest failure available — "not measured" is an ordinary thing to see —
 * and it is the shape {@code CompoundingSurfaceContractTest} was written for.
 */
class AnalystSurfaceContractTest {

    /** Every key {@code analyst-cells.js} dereferences. */
    private static final List<String> WIRE_FIELDS = List.of(
            "symbol", "companyName", "brokerage", "rating", "action", "targetPrice",
            "issuedOn", "resolvesOn", "horizonDays", "horizonStated", "priceAtCall",
            "upsidePctAtCall", "direction", "status", "reachedOn", "daysToReach",
            "lastPrice", "returnPct", "niftyReturnPct", "excessReturnPct",
            "maxFavourablePct", "headline", "sourceName", "sourceUrl", "lastMeasuredAt",
            "calledOn", "brokerStatedPrice");

    @Test
    @DisplayName("Every field the analyst cells read is on the wire")
    void wireContract() {
        Map<String, Object> row = AnalystTargetViewService.row(AnalystTargetEntity.builder()
                .symbol("NSE:X").brokerage("Nomura").targetPrice(100.0)
                .issuedOn(LocalDate.now()).horizonDays(365).resolvesOn(LocalDate.now().plusDays(365))
                .status(AnalystTargetStatus.PENDING.name()).dedupKey("k")
                .build());

        assertThat(row.keySet()).containsAll(WIRE_FIELDS);
    }

    /**
     * The plausibility fields `plausibilityBlock` dereferences (SPEC §49.13).
     *
     * <p>Same reason as every other contract test here: nothing type-checks the wire, so a rename
     * does not fail — the cell finds {@code undefined} and draws "not measured" for ever, on a
     * stock the app measured perfectly well. That failure is quieter than a crash and, on this
     * panel in particular, indistinguishable from the honest answer.
     */
    @Test
    @DisplayName("Every plausibility field the panel reads is on the wire, and nullable")
    void plausibilityWireContract() {
        Map<String, Object> m = AnalystTargetViewService.readMap(
                AnalystTargetPlausibility.forTarget(12.0,
                        new AnalystTargetPlausibility.GrowthRecord(8.0, 6, 2020, 2025, "n"),
                        1000, 1400));

        assertThat(m.keySet()).containsAll(List.of(
                "verdict", "requiredGrowthPercent", "impliedGrowthPercent",
                "historicalGrowthPercent", "yearsOfRecord", "gapVsHistoryPoints",
                "gapVsTodayPoints", "reason"));

        // Every numeric field must be a wrapper carrying null through, or an unmeasured reading
        // renders as a zero - the one thing SPEC 21 rule 7 forbids outright.
        Map<String, Object> none = AnalystTargetViewService.readMap(
                AnalystTargetPlausibility.forTarget(null, null, 1000, 1400));
        assertThat(none.get("requiredGrowthPercent")).isNull();
        assertThat(none.get("gapVsHistoryPoints")).isNull();
        assertThat(none.get("verdict")).isEqualTo("NOT_MEASURED");

        // The panel renders one badge per verdict; a value with no entry in its table would draw
        // as the unmeasured marker on a stock that WAS measured.
        assertThat(AnalystTargetPlausibility.Verdict.values()).hasSize(7);
    }

    @Test
    @DisplayName("The status vocabulary contains no instruction to transact")
    void vocabularyIsNotASignal() {
        // SPEC §20 rule 10. "Is it a good time to buy" has exactly one rule table in this app
        // (BuyTimingVerdict, Gotcha 85), and a ledger of other people's opinions must not become
        // a second one. Every value describes what happened to a claim.
        List<String> banned = List.of("BUY", "SELL", "HOLD", "ACCUMULATE", "AVOID", "EXIT",
                "ENTER", "ADD", "REDUCE", "BOOK", "TARGET_HIT_BUY");

        for (AnalystTargetStatus s : AnalystTargetStatus.values()) {
            String name = s.name().toUpperCase(Locale.ROOT);
            assertThat(banned).as("status %s reads as an instruction", name).doesNotContain(name);
        }
    }

    @Test
    @DisplayName("A rating is recorded, and NOT_STATED is an absence rather than a Hold")
    void ratingAbsenceIsItsOwnValue() {
        // A headline with no rating has not said "hold". Collapsing the two would invent a
        // neutral opinion for every house that only published a number.
        assertThat(AnalystTargetParser.Rating.values())
                .contains(AnalystTargetParser.Rating.NOT_STATED);
        assertThat(Arrays.stream(AnalystTargetParser.Rating.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrder("BUY", "HOLD", "SELL", "NOT_STATED");
    }

    @Test
    @DisplayName("The ledger ships in shadow mode with no bonus figure to switch on")
    void shipsInShadowMode() {
        // Gotcha 30, and the macro lens's precedent. A third party's opinion arriving through a
        // news headline is the exact input shape of the two engines deleted on 2026-09-03 for
        // never having produced a measured hit rate.
        com.example.trading.multibagger.MultibaggerConfig config =
                new com.example.trading.multibagger.MultibaggerConfig();

        assertThat(config.isAnalystTargetActionable()).isFalse();

        // The absence is as load-bearing as the flag: choosing how many points a brokerage
        // upgrade is worth, before the measurement has shown it even has a sign, is the failure
        // the shadow rule exists to prevent. A reviewer adding one has to argue for the number.
        assertThat(Arrays.stream(com.example.trading.multibagger.MultibaggerConfig.class
                        .getDeclaredFields()).map(java.lang.reflect.Field::getName).toList())
                .as("an analyst-target bonus must not be added without argument")
                .doesNotContain("analystTargetBonus");
    }

    @Test
    @DisplayName("The default horizon is applied as a convention and recorded as one")
    void horizonDefaultIsMarkedAsAnAssumption() {
        // B-057 failed 14 of 33 holdings on a seeded horizon nobody chose; B-097 counted 45
        // app-generated theses as the investor's own. A default is not a statement.
        AnalystTargetConfig config = new AnalystTargetConfig();
        assertThat(config.getDefaultHorizonDays()).isEqualTo(365);

        AnalystTargetEntity assumed = AnalystTargetEntity.builder().horizonStated(false).build();
        assertThat(assumed.isHorizonStated()).isFalse();
    }

    @Test
    @DisplayName("A house needs more than a couple of resolved calls before a hit rate appears")
    void trackRecordFloorIsSet() {
        assertThat(new AnalystTargetConfig().getMinResolvedForTrackRecord()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("The line above the table never contradicts the table")
    void summaryNoteMatchesWhatIsOnFile() {
        // Found by running it, not in review: the first version printed "no analyst target has been
        // recorded for this stock" directly above a recorded target that simply had not been priced
        // yet. Nothing on file, something unmeasurable, and something already resolved are three
        // different facts (Gotcha 21, 44).
        AnalystTargetEntity unpriced = AnalystTargetEntity.builder()
                .status(AnalystTargetStatus.UNPRICED.name()).build();
        AnalystTargetEntity resolved = AnalystTargetEntity.builder()
                .status(AnalystTargetStatus.MISSED.name()).build();
        AnalystTargetEntity running = AnalystTargetEntity.builder()
                .status(AnalystTargetStatus.PENDING.name()).build();

        assertThat(AnalystTargetViewService.note(List.of(), List.of(), 0))
                .contains("No analyst target has been recorded");
        assertThat(AnalystTargetViewService.note(List.of(unpriced), List.of(), 0))
                .contains("not measurable yet")
                .doesNotContain("No analyst target has been recorded");
        assertThat(AnalystTargetViewService.note(List.of(resolved), List.of(), 0))
                .contains("No target is currently running")
                .doesNotContain("No analyst target has been recorded");
        assertThat(AnalystTargetViewService.note(List.of(running), List.of(running), 1))
                .contains("One brokerage");
    }

    @Test
    @DisplayName("Coverage reports 'unknown' rather than 'none' when the ledger is not wired up")
    void unregisteredCoverageIsUnknown() {
        // A signal that has never been connected would otherwise read as 0% coverage, which looks
        // like a measured finding about the feed rather than a missing wire (B-074's lesson).
        AnalystTargetCoverage.register(null);
        AnalystTargetCoverage.invalidate();
        assertThat(AnalystTargetCoverage.available()).isFalse();
    }
}
