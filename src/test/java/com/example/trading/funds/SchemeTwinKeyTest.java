package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §52.8 - pairing the two share classes of one scheme.
 *
 * <p>The asymmetry decides every case here. A <b>missed</b> pair costs one unmeasured commission,
 * reported honestly. A <b>wrong</b> pair computes the gap between two different portfolios and
 * publishes it as a commission - a confident, specific, fictional number.
 */
class SchemeTwinKeyTest {

    @Test
    @DisplayName("the two plans of one scheme share a key despite different codes and ISINs")
    void twoPlansOfOneSchemeMatch() {
        // Real spellings from the live file: AMFI gives these separate scheme codes and separate
        // ISINs, and the only thing linking them is the name.
        String direct = SchemeTwinKey.of("Axis Mutual Fund",
                "Axis Midcap Fund - Direct Plan - Growth Option");
        String regular = SchemeTwinKey.of("Axis Mutual Fund",
                "Axis Midcap Fund - Regular Plan - Growth");

        assertThat(direct).isEqualTo(regular);
    }

    @Test
    @DisplayName("GROWTH OPTION is stripped as a phrase, not leaving GROWTH behind")
    void longerPlanPhrasesAreStrippedFirst() {
        // Stripping a bare OPTION first would leave "GROWTH" in one key and not the other, and
        // split a single scheme into two - one of which would then find no twin.
        String a = SchemeTwinKey.of("HDFC Mutual Fund", "HDFC Flexi Cap Fund - Growth Option");
        String b = SchemeTwinKey.of("HDFC Mutual Fund", "HDFC Flexi Cap Fund - Growth");

        assertThat(a).isEqualTo(b);
        assertThat(a).doesNotContain("GROWTH");
    }

    @Test
    @DisplayName("two different schemes from one AMC never share a key")
    void differentSchemesDoNotCollide() {
        String mid = SchemeTwinKey.of("Axis Mutual Fund", "Axis Midcap Fund - Direct Plan - Growth");
        String small = SchemeTwinKey.of("Axis Mutual Fund", "Axis Small Cap Fund - Direct Plan - Growth");

        assertThat(mid).isNotEqualTo(small);
    }

    @Test
    @DisplayName("the same scheme name at two fund houses never pairs across them")
    void amcIsPartOfTheKey() {
        String a = SchemeTwinKey.of("Axis Mutual Fund", "Midcap Fund - Direct Plan - Growth");
        String b = SchemeTwinKey.of("Nippon India Mutual Fund", "Midcap Fund - Regular Plan - Growth");

        // Without the AMC in the key, one house's Direct plan would be paired against another
        // house's Regular plan and the difference between two unrelated portfolios published as a
        // commission.
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("a missing name yields null rather than a blank key that pairs everything")
    void missingInputYieldsNull() {
        assertThat(SchemeTwinKey.of(null, "Axis Midcap Fund - Direct Plan")).isNull();
        assertThat(SchemeTwinKey.of("Axis Mutual Fund", null)).isNull();
        assertThat(SchemeTwinKey.of("Axis Mutual Fund", "   ")).isNull();
        // A name that is nothing but plan words leaves no core to match on.
        assertThat(SchemeTwinKey.of("Axis Mutual Fund", "Direct Plan - Growth Option")).isNull();
    }

    @Test
    @DisplayName("punctuation and spacing differences between the two plans do not break the pair")
    void punctuationIsNormalised() {
        String a = SchemeTwinKey.of("SBI Mutual Fund", "SBI Blue Chip Fund-Direct Plan-Growth");
        String b = SchemeTwinKey.of("SBI Mutual Fund", "SBI  Blue Chip Fund - Regular Plan - Growth");

        assertThat(a).isEqualTo(b);
    }
}
