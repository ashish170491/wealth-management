package com.example.trading.universe.theme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the coverage arithmetic (SPEC §51.3) — the half of the feature that answers the question
 * a theme column cannot.
 *
 * <p>The properties that matter, and why each one is here rather than assumed:
 *
 * <ul>
 *   <li><b>An unverified member leaves the percentage entirely.</b> Counting it as covered
 *       overstates; counting it as a gap understates. Either way a typo in the map would move a
 *       coverage figure, which is how a hand-kept file quietly becomes untrustworthy.</li>
 *   <li><b>No confirmable members means a null percentage, not 0%.</b> "Nothing we can count" and
 *       "we cover none of it" are different statements and only the second is a finding
 *       (Gotcha 21, Gotcha 68).</li>
 *   <li><b>The caveat always names the denominator.</b> A bare "8 of 8" reads as a fully
 *       researched sector; the figure is only ever about a hand-kept list.</li>
 * </ul>
 *
 * <p>Pure: no Spring context, no repository, no clock.
 */
class ThemeCoverageTest {

    private static UniverseThemes.Tag tag(String symbol, boolean verified) {
        return new UniverseThemes.Tag(symbol, ThemeCatalog.SEMICONDUCTORS, "ISM", "OSAT", verified);
    }

    @Test
    @DisplayName("a screened member is covered, an unscreened confirmed one is a gap")
    void splitsCoveredFromGap() {
        var c = ThemeCoverage.forTheme(ThemeCatalog.SEMICONDUCTORS,
                List.of(tag("AAA", true), tag("BBB", true)),
                Set.of("AAA"), Set.of());

        assertThat(c.covered()).isEqualTo(1);
        assertThat(c.gaps()).isEqualTo(1);
        assertThat(c.unverified()).isZero();
        assertThat(c.coveragePercent()).isEqualTo(50.0);
        assertThat(c.gapMembers()).extracting(ThemeCoverage.Member::symbol).containsExactly("BBB");
    }

    @Test
    @DisplayName("an unverified member counts in neither half of the percentage")
    void unverifiedLeavesTheDenominator() {
        // Two confirmable names, one covered -> 50%. The third is unconfirmed and must not move
        // that number in either direction, or a bad ticker becomes a coverage claim.
        var c = ThemeCoverage.forTheme(ThemeCatalog.SEMICONDUCTORS,
                List.of(tag("AAA", true), tag("BBB", true), tag("CCC", false)),
                Set.of("AAA"), Set.of());

        assertThat(c.tagged()).isEqualTo(3);
        assertThat(c.unverified()).isEqualTo(1);
        assertThat(c.coveragePercent()).isEqualTo(50.0);
        // And it is not silently a gap either: the gap list is the action list, and an
        // unconfirmed ticker is not something to go and add.
        assertThat(c.gapMembers()).extracting(ThemeCoverage.Member::symbol).containsExactly("BBB");
    }

    @Test
    @DisplayName("a theme of only unconfirmed names has NO coverage figure, not a zero")
    void noConfirmableMembersIsNullNotZero() {
        var c = ThemeCoverage.forTheme(ThemeCatalog.SEMICONDUCTORS,
                List.of(tag("AAA", false), tag("BBB", false)), Set.of(), Set.of());

        assertThat(c.unverified()).isEqualTo(2);
        // Null, never 0.0. A zero would render as "we cover none of this theme", which is a
        // finding about the app; the truth is that there is nothing here it can count.
        assertThat(c.coveragePercent()).isNull();
    }

    @Test
    @DisplayName("an unscreened member is a gap even when the investor already owns it")
    void ownershipDoesNotImplyCoverage() {
        // Owning a stock does not mean the app analyses it — that was the A0(b) finding in 2026,
        // where seven holdings sat outside the screening universe and could never be classified.
        var c = ThemeCoverage.forTheme(ThemeCatalog.SEMICONDUCTORS,
                List.of(tag("AAA", true)), Set.of(), Set.of("AAA"));

        assertThat(c.held()).isEqualTo(1);
        assertThat(c.gaps()).isEqualTo(1);
        assertThat(c.covered()).isZero();
        assertThat(c.members().get(0).status()).isEqualTo(ThemeCoverage.Status.NOT_SCREENED);
        assertThat(c.members().get(0).held()).isTrue();
    }

    @Test
    @DisplayName("a screened member is covered even when the sector table cannot confirm it")
    void screeningOutranksTheSectorTable() {
        // Found by running the live screen, not in review. `verified` only asks whether
        // universe-sectors.csv names the ticker; being in the screening universe is strictly
        // better evidence, because the app fetched candles for it and produced a composite.
        // Checking verified first reported CENTUM - a stock this app screens - as a ticker it
        // could not confirm, and silently shrank the coverage denominator.
        var c = ThemeCoverage.forTheme(ThemeCatalog.SEMICONDUCTORS,
                List.of(tag("CENTUM", false)), Set.of("CENTUM"), Set.of());

        assertThat(c.covered()).isEqualTo(1);
        assertThat(c.unverified()).isZero();
        assertThat(c.members().get(0).status()).isEqualTo(ThemeCoverage.Status.SCREENED);
        assertThat(c.coveragePercent()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("the caveat names the denominator and mentions the unconfirmed names when there are any")
    void caveatNamesItsDenominator() {
        var withUnverified = ThemeCoverage.forTheme(ThemeCatalog.SEMICONDUCTORS,
                List.of(tag("AAA", true), tag("CCC", false)), Set.of("AAA"), Set.of());
        assertThat(withUnverified.caveat()).contains("2 businesses");
        assertThat(withUnverified.caveat()).contains("could not be confirmed");
        // And it never lets "screened" be read as approval.
        assertThat(withUnverified.caveat()).contains("never that it is worth owning");

        var clean = ThemeCoverage.forTheme(ThemeCatalog.SEMICONDUCTORS,
                List.of(tag("AAA", true)), Set.of("AAA"), Set.of());
        assertThat(clean.caveat()).contains("1 business");
        assertThat(clean.caveat()).doesNotContain("could not be confirmed");
    }

    @Test
    @DisplayName("normalise strips exchange prefixes and case so the two sides can meet")
    void normaliseMakesTheJoinPossible() {
        // The screening universe is prefixed and the map is bare. Comparing them unnormalised
        // finds nothing and reports every theme at 0% — a broken feature that looks like a
        // finding, which is the worst failure mode available here.
        // Arrays.asList, not List.of: the null is the point of the case and List.of rejects one.
        Set<String> out = ThemeCoverage.normalise(
                java.util.Arrays.asList("NSE:KAYNES", "BSE:wabag", " NETWEB ", "", null));
        assertThat(out).containsExactlyInAnyOrder("KAYNES", "WABAG", "NETWEB");
        assertThat(ThemeCoverage.normalise(null)).isEmpty();
    }

    @Test
    @DisplayName("over the shipped map, the three states always account for every tagged row")
    void statesAreExhaustiveOverTheRealFile() {
        // Guards against a fourth state appearing without the arithmetic being updated: if the
        // parts stop summing to the whole, some member is being dropped from every count.
        for (ThemeCoverage.Coverage c : ThemeCoverage.all(Set.of("KAYNES"), Set.of())) {
            assertThat(c.covered() + c.gaps() + c.unverified())
                    .as("%s: covered + gaps + unverified must equal tagged", c.theme())
                    .isEqualTo(c.tagged());
            assertThat(c.members()).hasSize(c.tagged());
        }
    }

    @Test
    @DisplayName("the shipped map reports a real gap list, so the screen has something to say")
    void shippedMapHasSomethingToReport() {
        // Against an empty universe every confirmable member is a gap. This is the sanity check
        // that the join works at all on the real file rather than only on fixtures.
        var all = ThemeCoverage.all(Set.of(), Set.of());
        int gaps = all.stream().mapToInt(ThemeCoverage.Coverage::gaps).sum();
        assertThat(gaps).isGreaterThan(20);
    }
}
