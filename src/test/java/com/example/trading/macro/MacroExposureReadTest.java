package com.example.trading.macro;

import com.example.trading.macro.MacroExposureMap.Entry;
import com.example.trading.macro.MacroExposureMap.OnRise;
import com.example.trading.macro.MacroExposureMap.Scope;
import com.example.trading.macro.MacroExposureMap.Strength;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static com.example.trading.macro.MacroExposureRead.Verdict.HEADWIND;
import static com.example.trading.macro.MacroExposureRead.Verdict.MIXED;
import static com.example.trading.macro.MacroExposureRead.Verdict.NOT_EXPOSED;
import static com.example.trading.macro.MacroExposureRead.Verdict.NOT_MEASURED;
import static com.example.trading.macro.MacroExposureRead.Verdict.TAILWIND;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the macro exposure join (SPEC §48.4).
 *
 * <p>The properties that matter: an unmapped business is NOT_MEASURED and never collapses into
 * NOT_EXPOSED; one event is a tailwind for an exporter and a headwind for an importer at the same
 * time; conflicting effects report as MIXED rather than netting to nothing; a lender's balance
 * sheet never triggers the leverage adjustment; and the vocabulary contains no instruction to
 * transact anywhere in it, verdicts or prose.
 */
class MacroExposureReadTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);

    private static Entry rule(MacroFactor factor, Scope scope, String key, OnRise onRise, Strength strength) {
        return new Entry(factor, scope, key, onRise, strength, "input cost",
                "A sentence the reader can check the rule against.");
    }

    private static MacroExposureRead.Event event(long id, MacroFactor factor, MacroDirection direction,
                                                 LocalDate on) {
        return new MacroExposureRead.Event(id, factor, direction, MacroMagnitude.MODERATE, on,
                factor.label() + " " + direction.pastTense(), false);
    }

    private static MacroExposureRead.Result read(List<Entry> rules, List<MacroExposureRead.Event> events) {
        return read(rules, events, false, null);
    }

    private static MacroExposureRead.Result read(List<Entry> rules, List<MacroExposureRead.Event> events,
                                                 boolean lender, Double debtToEquity) {
        return MacroExposureRead.read(new MacroExposureRead.Input(
                "NSE:TEST", "IT", lender, debtToEquity, rules, events, TODAY, 14));
    }

    // ------------------------------------------------------------------ the two absences

    @Test
    @DisplayName("A business with no rule in the map is NOT_MEASURED, even when events happened")
    void unmappedBusinessIsNotMeasured() {
        MacroExposureRead.Result r = read(List.of(),
                List.of(event(1, MacroFactor.CRUDE_OIL, MacroDirection.UP, TODAY.minusDays(2))));

        assertThat(r.verdict()).isEqualTo(NOT_MEASURED);
        assertThat(r.strength()).isNull();
        assertThat(r.reasons()).isEmpty();
        assertThat(r.note()).contains("gap in the map");
    }

    @Test
    @DisplayName("A mapped business with nothing matching is NOT_EXPOSED - a finding, not a gap")
    void mappedButUntouchedIsNotExposed() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of(event(1, MacroFactor.GOLD, MacroDirection.UP, TODAY.minusDays(1))));

        assertThat(r.verdict()).isEqualTo(NOT_EXPOSED);
        assertThat(r.measured()).isTrue();
        assertThat(r.eventsConsidered()).isEqualTo(1);
        assertThat(r.note()).contains("none of them touches this business");
    }

    @Test
    @DisplayName("NOT_EXPOSED with no events at all says so, so a quiet fortnight is legible")
    void quietWindowSaysNothingHappened() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of());

        assertThat(r.verdict()).isEqualTo(NOT_EXPOSED);
        assertThat(r.eventsConsidered()).isZero();
        assertThat(r.note()).contains("No macro event was recorded");
    }

    // ------------------------------------------------------------------ direction

    @Test
    @DisplayName("One rupee event is a tailwind for the exporter and a headwind for the importer")
    void oneEventTwoDirections() {
        MacroExposureRead.Event rupeeWeakens =
                event(7, MacroFactor.USDINR, MacroDirection.UP, TODAY.minusDays(3));

        MacroExposureRead.Result exporter = read(
                List.of(rule(MacroFactor.USDINR, Scope.SECTOR, "IT", OnRise.HELPED, Strength.HIGH)),
                List.of(rupeeWeakens));
        MacroExposureRead.Result importer = read(
                List.of(rule(MacroFactor.USDINR, Scope.SYMBOL, "INDIGO", OnRise.HURT, Strength.HIGH)),
                List.of(rupeeWeakens));

        assertThat(exporter.verdict()).isEqualTo(TAILWIND);
        assertThat(importer.verdict()).isEqualTo(HEADWIND);
    }

    @Test
    @DisplayName("A fall in a factor that helps on the way up is a headwind")
    void fallInverts() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SYMBOL, "ONGC", OnRise.HELPED, Strength.HIGH)),
                List.of(event(1, MacroFactor.CRUDE_OIL, MacroDirection.DOWN, TODAY.minusDays(1))));

        assertThat(r.verdict()).isEqualTo(HEADWIND);
    }

    // ------------------------------------------------------------------ mixing

    @Test
    @DisplayName("A tailwind and a headwind together are MIXED, never netted to nothing")
    void conflictingEventsAreMixed() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.USDINR, Scope.SECTOR, "IT", OnRise.HELPED, Strength.HIGH),
                        rule(MacroFactor.GLOBAL_DEMAND_SLOWDOWN, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of(event(1, MacroFactor.USDINR, MacroDirection.UP, TODAY.minusDays(2)),
                        event(2, MacroFactor.GLOBAL_DEMAND_SLOWDOWN, MacroDirection.UP, TODAY.minusDays(1))));

        assertThat(r.verdict()).isEqualTo(MIXED);
        assertThat(r.reasons()).hasSize(2);
    }

    @Test
    @DisplayName("A rule that is MIXED in the map makes the reading MIXED on its own")
    void mixedRuleIsMixed() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SYMBOL, "RELIANCE", OnRise.MIXED, Strength.MEDIUM)),
                List.of(event(1, MacroFactor.CRUDE_OIL, MacroDirection.UP, TODAY.minusDays(1))));

        assertThat(r.verdict()).isEqualTo(MIXED);
    }

    // ------------------------------------------------------------------ the window

    @Test
    @DisplayName("An event older than the window is ignored")
    void staleEventIgnored() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of(event(1, MacroFactor.CRUDE_OIL, MacroDirection.UP, TODAY.minusDays(20))));

        assertThat(r.verdict()).isEqualTo(NOT_EXPOSED);
        assertThat(r.eventsConsidered()).isZero();
    }

    @Test
    @DisplayName("A future-dated event never counts - it belongs to the calendar, and it would never age out")
    void futureEventIgnored() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.INTEREST_RATES, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of(event(1, MacroFactor.INTEREST_RATES, MacroDirection.UP, TODAY.plusDays(5))));

        assertThat(r.verdict()).isEqualTo(NOT_EXPOSED);
        assertThat(r.eventsConsidered()).isZero();
    }

    @Test
    @DisplayName("A dismissed event stops counting but the reading still reports the rest")
    void dismissedEventIgnored() {
        MacroExposureRead.Event dismissed = new MacroExposureRead.Event(1, MacroFactor.CRUDE_OIL,
                MacroDirection.UP, MacroMagnitude.LARGE, TODAY.minusDays(1), "noise", true);

        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of(dismissed));

        assertThat(r.verdict()).isEqualTo(NOT_EXPOSED);
        assertThat(r.eventsConsidered()).isZero();
    }

    // ------------------------------------------------------------------ leverage and lenders

    @Test
    @DisplayName("A leveraged borrower's rate reading is raised to high")
    void leverageRaisesRateStrength() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.INTEREST_RATES, Scope.SECTOR, "REALTY", OnRise.HURT, Strength.MEDIUM)),
                List.of(event(1, MacroFactor.INTEREST_RATES, MacroDirection.UP, TODAY.minusDays(1))),
                false, 2.4);

        assertThat(r.verdict()).isEqualTo(HEADWIND);
        assertThat(r.strength()).isEqualTo(Strength.HIGH);
        assertThat(r.reasons().get(0).text()).contains("enough debt for it to matter more than usual");
    }

    @Test
    @DisplayName("A lender's debt-to-equity never raises it: a bank is funded by debt by construction")
    void lenderLeverageNeverUpgrades() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.INTEREST_RATES, Scope.SECTOR, "BANKING", OnRise.HELPED, Strength.LOW)),
                List.of(event(1, MacroFactor.INTEREST_RATES, MacroDirection.UP, TODAY.minusDays(1))),
                true, 7.5);

        assertThat(r.verdict()).isEqualTo(TAILWIND);
        assertThat(r.strength()).isEqualTo(Strength.LOW);
    }

    @Test
    @DisplayName("An unmeasured debt-to-equity leaves the strength alone rather than assuming the worst")
    void unknownLeverageChangesNothing() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.INTEREST_RATES, Scope.SECTOR, "AUTO", OnRise.HURT, Strength.MEDIUM)),
                List.of(event(1, MacroFactor.INTEREST_RATES, MacroDirection.UP, TODAY.minusDays(1))),
                false, null);

        assertThat(r.strength()).isEqualTo(Strength.MEDIUM);
    }

    @Test
    @DisplayName("Leverage does not touch a non-rate factor - a crude rise is not about the balance sheet")
    void leverageOnlyAppliesToRates() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SECTOR, "CHEMICALS", OnRise.HURT, Strength.LOW)),
                List.of(event(1, MacroFactor.CRUDE_OIL, MacroDirection.UP, TODAY.minusDays(1))),
                false, 3.0);

        assertThat(r.strength()).isEqualTo(Strength.LOW);
    }

    // ------------------------------------------------------------------ presentation contract

    @Test
    @DisplayName("The strongest reason leads, so a one-line cell shows the line that matters")
    void strongestReasonFirst() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.USDINR, Scope.SECTOR, "IT", OnRise.HURT, Strength.LOW),
                        rule(MacroFactor.CRUDE_OIL, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of(event(1, MacroFactor.USDINR, MacroDirection.UP, TODAY.minusDays(1)),
                        event(2, MacroFactor.CRUDE_OIL, MacroDirection.UP, TODAY.minusDays(4))));

        assertThat(r.reasons().get(0).factor()).isEqualTo(MacroFactor.CRUDE_OIL);
        assertThat(r.answeredBy()).isEqualTo("SECTOR:IT");
    }

    @Test
    @DisplayName("Every reason names its channel and carries the map's own sentence")
    void reasonsAreCheckable() {
        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of(event(1, MacroFactor.CRUDE_OIL, MacroDirection.UP, TODAY.minusDays(1))));

        MacroExposureRead.Reason reason = r.reasons().get(0);
        assertThat(reason.channel()).isNotBlank();
        assertThat(reason.rationale()).isNotBlank();
        assertThat(reason.text()).contains("Crude oil").contains("input cost");
        assertThat(reason.occurredAt()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    @DisplayName("Nothing in the vocabulary tells the investor to transact (SPEC 19, SPEC 20 rule 10)")
    void vocabularyNeverSaysBuyOrSell() {
        for (MacroExposureRead.Verdict v : MacroExposureRead.Verdict.values()) {
            assertThat(v.name()).doesNotContain("BUY").doesNotContain("SELL")
                    .doesNotContain("EXIT").doesNotContain("HOLD").doesNotContain("ACCUMULATE");
        }

        MacroExposureRead.Result r = read(
                List.of(rule(MacroFactor.CRUDE_OIL, Scope.SECTOR, "IT", OnRise.HURT, Strength.HIGH)),
                List.of(event(1, MacroFactor.CRUDE_OIL, MacroDirection.UP, TODAY.minusDays(1))));

        String prose = (r.reasons().get(0).text() + " " + r.reasons().get(0).rationale())
                .toLowerCase(Locale.ROOT);
        assertThat(prose)
                .doesNotContain(" buy").doesNotContain(" sell")
                .doesNotContain("exit").doesNotContain("book profit")
                .doesNotContain("accumulate");
    }
}
