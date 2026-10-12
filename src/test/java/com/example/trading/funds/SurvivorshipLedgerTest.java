package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/** SPEC §52.9 - a measured attrition number replacing the usual survivorship caveat. */
class SurvivorshipLedgerTest {

    private static final YearMonth FROM = YearMonth.of(2019, 1);
    private static final YearMonth TO = YearMonth.of(2026, 8);

    @Test
    @DisplayName("schemes that stopped reporting are counted, not waved at")
    void attritionIsCounted() {
        Set<Long> atStart = Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        Set<Long> atEnd = Set.of(1L, 2L, 3L, 4L, 5L, 6L, 99L);

        SurvivorshipLedger l = SurvivorshipLedger.between(SebiCategory.FLEXI_CAP, FROM, TO, atStart, atEnd);

        assertThat(l.reportingAtStart()).isEqualTo(10);
        assertThat(l.stillReporting()).isEqualTo(6);
        assertThat(l.noLongerReporting()).isEqualTo(4);
        assertThat(l.attritionPct()).isCloseTo(40.0, offset(0.01));
        assertThat(l.reason()).contains("no longer report");
    }

    @Test
    @DisplayName("a scheme launched after the start month never counts as a survivor of it")
    void laterLaunchesDoNotInflateSurvival() {
        Set<Long> atStart = Set.of(1L, 2L);
        Set<Long> atEnd = Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);

        SurvivorshipLedger l = SurvivorshipLedger.between(SebiCategory.MID_CAP, FROM, TO, atStart, atEnd);

        // Attrition is about the choice an investor faced at the start, so the denominator is the
        // start cross-section. Counting today's larger category would report 0% attrition for a
        // category that lost half its schemes.
        assertThat(l.reportingAtStart()).isEqualTo(2);
        assertThat(l.attritionPct()).isZero();
    }

    @Test
    @DisplayName("an unfetched start month is a gap in the archive, never zero attrition")
    void missingStartMonthIsNotAFinding() {
        SurvivorshipLedger l = SurvivorshipLedger.between(SebiCategory.SMALL_CAP, FROM, TO, Set.of(), Set.of());

        assertThat(l.attritionPct()).as("null, never 0.0").isNull();
        assertThat(l.reason())
                .contains("gap in this app's archive")
                .contains("not a finding that no scheme has ever closed");
    }

    @Test
    @DisplayName("the caveat attaches to the peer comparison, never to the fund's own record")
    void caveatSeparatesTheFundFromItsPeers() {
        SurvivorshipLedger l = SurvivorshipLedger.between(SebiCategory.ELSS, FROM, TO,
                Set.of(1L, 2L, 3L), Set.of(1L));

        // Conflating the two lets a real finding about a fund be waved off with a general caveat.
        assertThat(l.caveat())
                .contains("peer comparison only")
                .contains("a fund's own record is the record of a fund that is still here");
    }

    @Test
    @DisplayName("everything surviving reads 0% attrition, which is a measurement and not an absence")
    void fullSurvivalIsAMeasurement() {
        Set<Long> both = Set.of(1L, 2L, 3L);

        SurvivorshipLedger l = SurvivorshipLedger.between(SebiCategory.LARGE_CAP, FROM, TO, both, both);

        assertThat(l.attritionPct()).isZero();
        assertThat(l.noLongerReporting()).isZero();
    }
}
