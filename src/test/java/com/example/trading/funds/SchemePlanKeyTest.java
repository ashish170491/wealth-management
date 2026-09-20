package com.example.trading.funds;

import com.example.trading.funds.SchemePlanKey.Option;
import com.example.trading.funds.SchemePlanKey.Plan;
import com.example.trading.funds.SchemePlanKey.Resolved;
import com.example.trading.funds.SchemePlanKey.ResolvedFrom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the plan and option resolver (SPEC §52).
 *
 * <p>Both halves carry a units error of the B-047 family. <b>Growth vs IDCW</b> is the quieter and
 * more dangerous one: an IDCW series has every distribution taken out of its NAV, so a CAGR
 * computed on it understates the fund, and setting it against a Growth series compares two things
 * measured on different scales — exactly B-047 (a quarter filed as a year) and B-113 (two years
 * against ten). <b>Direct vs Regular</b> is the louder one: they are one portfolio under two fee
 * schedules, so a peer set holding both counts every fund twice at two different returns.
 */
class SchemePlanKeyTest {

    @Test
    @DisplayName("AMFI's several spellings of Growth all resolve to Growth")
    void growthSpellingsResolve() {
        // Measured on the live file: Growth, Growth Option and GROWTH are all present.
        for (String spelling : new String[]{"Growth", "Growth Option", "GROWTH", "growth"}) {
            Resolved r = SchemePlanKey.resolve("Direct Plan", spelling, "Some Fund");
            assertThat(r.option()).as(spelling).isEqualTo(Option.GROWTH);
            assertThat(r.optionFrom()).isEqualTo(ResolvedFrom.COLUMN);
        }
    }

    @Test
    @DisplayName("every distributing spelling resolves to IDCW, never to Growth")
    void idcwSpellingsResolve() {
        // SEBI's renaming spells it "IDCW (Income Distribution CUM Capital Withdrawal)", and AMCs
        // add Monthly/Quarterly/Payout/Reinvestment prefixes and suffixes.
        String[] spellings = {
                "IDCW", "IDCW Option", "Monthly IDCW", "Quarterly IDCW",
                "IDCW (Income Distribution CUM Capital Withdrawal)",
                "IDCW Payout", "IDCW-Re-investment", "Dividend", "Dividend Payout"
        };
        for (String spelling : spellings) {
            assertThat(SchemePlanKey.resolve("Direct Plan", spelling, "Some Fund").option())
                    .as(spelling)
                    .isEqualTo(Option.IDCW);
        }
    }

    @Test
    @DisplayName("a label naming both a distribution and growth is IDCW — Gotcha 53, positive direction")
    void distributionWinsOverGrowthInAMixedLabel() {
        // Some AMCs write "IDCW Payout - Growth Option" style labels. A row that mentions a
        // distribution is a distributing series whatever else it says, and reading it as Growth
        // would file a payout-depressed NAV as a total-return one. Before matching a keyword,
        // check what innocently contains it.
        assertThat(SchemePlanKey.resolve("Direct Plan", "IDCW Payout - Growth Option", "X").option())
                .isEqualTo(Option.IDCW);
    }

    @Test
    @DisplayName("Direct and Regular are told apart, and neither is ever defaulted")
    void plansResolve() {
        assertThat(SchemePlanKey.resolve("Direct Plan", "Growth", "X").plan()).isEqualTo(Plan.DIRECT);
        assertThat(SchemePlanKey.resolve("Regular Plan", "Growth", "X").plan()).isEqualTo(Plan.REGULAR);
    }

    @Test
    @DisplayName("a blank plan column falls back to the scheme name, and records that it did")
    void nameFallbackIsRecorded() {
        Resolved r = SchemePlanKey.resolve("", "", "Some Fund - Direct Plan - Growth");

        assertThat(r.plan()).isEqualTo(Plan.DIRECT);
        assertThat(r.option()).isEqualTo(Option.GROWTH);
        // Which source answered must be recorded, so a reading can be traced rather than assumed
        // (Gotcha 84). Measured on the live file the name rescues only 8 rows, so this is a genuine
        // edge case rather than the common path.
        assertThat(r.planFrom()).isEqualTo(ResolvedFrom.SCHEME_NAME);
        assertThat(r.optionFrom()).isEqualTo(ResolvedFrom.SCHEME_NAME);
    }

    @Test
    @DisplayName("an unresolvable plan is UNSPECIFIED and is never guessed into Regular")
    void unresolvablePlanIsNotGuessed() {
        // Defaulting to Regular would put the row in a peer set it does not belong to and,
        // worse, would make the Direct-minus-Regular commission measurement wrong for that scheme.
        Resolved r = SchemePlanKey.resolve("", "", "ICICI Prudential Corporate Bond Fund");

        assertThat(r.plan()).isEqualTo(Plan.UNSPECIFIED);
        assertThat(r.planFrom()).isEqualTo(ResolvedFrom.UNRESOLVED);
        assertThat(r.isIncomplete()).as("must leave the peer set rather than be defaulted").isTrue();
        assertThat(r.isCanonicalForMeasurement()).isFalse();
    }

    @Test
    @DisplayName("an unresolvable option is UNSPECIFIED, never assumed to be Growth")
    void unresolvableOptionIsNotAssumedGrowth() {
        // Assuming Growth would silently admit a payout-depressed IDCW series into the measured set.
        Resolved r = SchemePlanKey.resolve("Direct Plan", "", "Some Fund With No Option Named");

        assertThat(r.option()).isEqualTo(Option.UNSPECIFIED);
        assertThat(r.isIncomplete()).isTrue();
    }

    @Test
    @DisplayName("only a Direct-plan Growth series is canonical for measurement")
    void canonicalIsDirectGrowthOnly() {
        assertThat(SchemePlanKey.resolve("Direct Plan", "Growth", "X").isCanonicalForMeasurement()).isTrue();
        assertThat(SchemePlanKey.resolve("Regular Plan", "Growth", "X").isCanonicalForMeasurement()).isFalse();
        assertThat(SchemePlanKey.resolve("Direct Plan", "IDCW", "X").isCanonicalForMeasurement()).isFalse();
    }

    @Test
    @DisplayName("null arguments do not throw")
    void nullSafe() {
        Resolved r = SchemePlanKey.resolve(null, null, null);
        assertThat(r.plan()).isEqualTo(Plan.UNSPECIFIED);
        assertThat(r.option()).isEqualTo(Option.UNSPECIFIED);
    }
}
