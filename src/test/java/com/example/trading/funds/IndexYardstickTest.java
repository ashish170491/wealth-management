package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §52.14 - which index fund may stand in for "what you would have got instead".
 *
 * <p>The mapping is made on scheme names because nothing links a scheme to the index it follows,
 * so the cases that matter are the ones where a name matches and must not.
 */
class IndexYardstickTest {

    private static Map<Long, String> funds(String... names) {
        Map<Long, String> m = new LinkedHashMap<>();
        long i = 1;
        for (String n : names) m.put(i++, n);
        return m;
    }

    @Test
    @DisplayName("a plain Nifty 50 tracker is the large-cap yardstick, and the match is auditable")
    void plainTrackerMatches() {
        IndexYardstick.Yardstick y = IndexYardstick.forCategory(SebiCategory.LARGE_CAP,
                funds("UTI Nifty 50 Index Fund - Direct Plan - Growth"));

        assertThat(y.available()).isTrue();
        assertThat(y.funds()).hasSize(1);
        // The phrase that matched travels with the match so a reader can reject it at a glance,
        // rather than having to trust the table (SPEC 51.10's rule).
        assertThat(y.funds().get(0).matchedPhrase()).isEqualTo("NIFTY 50");
    }

    @Test
    @DisplayName("an equal-weight or factor fund is refused even though its name contains the index")
    void factorProductsAreNotPlainTrackers() {
        // "Nifty 50 Equal Weight" contains "NIFTY 50" and is a different index with a materially
        // different return. Gotcha 53 in the positive direction: before matching on a phrase, check
        // what innocently contains it.
        Map<Long, String> tricky = funds(
                "DSP Nifty 50 Equal Weight Index Fund - Direct - Growth",
                "ICICI Prudential Nifty 50 Value 20 Index Fund - Direct - Growth",
                "Kotak Nifty 100 Low Volatility 30 Index Fund - Direct - Growth",
                "Nippon India Nifty 50 Momentum 30 Index Fund - Direct - Growth");

        IndexYardstick.Yardstick y = IndexYardstick.forCategory(SebiCategory.LARGE_CAP, tricky);

        assertThat(y.available()).isFalse();
        assertThat(y.funds()).isEmpty();
        for (String name : tricky.values()) {
            assertThat(IndexYardstick.isFactorProduct(name.toUpperCase()))
                    .as("should be rejected: %s", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a small-cap tracker never becomes the large-cap yardstick")
    void oneIndexNeverStandsInForAnother() {
        // The single most damaging failure this class could have: measuring large-cap funds against
        // a small-cap index and calling the gap skill. A bare "NIFTY" token would do exactly that,
        // which is why the table holds phrases.
        Map<Long, String> all = funds(
                "Nippon India Nifty Smallcap 250 Index Fund - Direct - Growth",
                "Motilal Oswal Nifty Midcap 150 Index Fund - Direct - Growth");

        assertThat(IndexYardstick.forCategory(SebiCategory.LARGE_CAP, all).available()).isFalse();
        assertThat(IndexYardstick.forCategory(SebiCategory.SMALL_CAP, all).funds())
                .singleElement()
                .satisfies(m -> assertThat(m.schemeName()).contains("Smallcap 250"));
        assertThat(IndexYardstick.forCategory(SebiCategory.MID_CAP, all).funds())
                .singleElement()
                .satisfies(m -> assertThat(m.schemeName()).contains("Midcap 150"));
    }

    @Test
    @DisplayName("a category with no honest tracker is refused BY NAME, never left silently empty")
    void categoriesWithoutAYardstickSayWhy() {
        // An empty column reads as "nothing to report". A named refusal reads as "this comparison
        // cannot be made honestly", which is the actual fact (SPEC 51's empty-column rule).
        Map<Long, String> everything = funds("UTI Nifty 50 Index Fund - Direct - Growth",
                "Motilal Oswal Nifty 500 Index Fund - Direct - Growth");

        for (SebiCategory c : new SebiCategory[]{SebiCategory.FOCUSED, SebiCategory.VALUE,
                SebiCategory.CONTRA, SebiCategory.DIVIDEND_YIELD, SebiCategory.ELSS,
                SebiCategory.SECTORAL_THEMATIC}) {
            IndexYardstick.Yardstick y = IndexYardstick.forCategory(c, everything);
            assertThat(y.available()).as("%s", c).isFalse();
            assertThat(y.reason()).as("%s", c).isNotBlank();
            assertThat(IndexYardstick.hasMapping(c)).as("%s", c).isFalse();
        }
    }

    @Test
    @DisplayName("no tracker on file is a gap in this app, not a statement about the category")
    void missingTrackerIsAGapNotAFinding() {
        IndexYardstick.Yardstick y = IndexYardstick.forCategory(SebiCategory.MID_CAP, Map.of());

        assertThat(y.available()).isFalse();
        assertThat(y.reason())
                .contains("a gap in this app's record")
                .contains("not a statement about");
    }

    @Test
    @DisplayName("several trackers of one index are all kept, so no single one decides the answer")
    void theMedianTrackerIsTheYardstick() {
        IndexYardstick.Yardstick y = IndexYardstick.forCategory(SebiCategory.FLEXI_CAP,
                funds("Motilal Oswal Nifty 500 Index Fund - Direct - Growth",
                        "HDFC NIFTY 500 Index Fund - Direct - Growth",
                        "Navi Nifty 500 Index Fund - Direct - Growth"));

        assertThat(y.funds()).hasSize(3);
        assertThat(y.reason()).contains("the median of them");
    }

    @Test
    @DisplayName("nothing here carries an instruction to transact")
    void carriesNoInstructionToTransact() {
        Map<Long, String> f = funds("UTI Nifty 50 Index Fund - Direct - Growth");
        for (SebiCategory c : SebiCategory.values()) {
            String reason = IndexYardstick.forCategory(c, f).reason();
            assertThat(reason.toLowerCase())
                    .as("%s: %s", c, reason)
                    .doesNotMatch("(?s).*\\b(buy|sell|exit|redeem|switch|recommend)\\b.*");
        }
    }
}
