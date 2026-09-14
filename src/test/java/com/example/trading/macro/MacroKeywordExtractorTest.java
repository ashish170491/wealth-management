package com.example.trading.macro;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the keyword fallback extractor (SPEC §48.4).
 *
 * <p>The properties that matter: a direction is part of the pattern rather than inferred from
 * sentiment, so "rupee falls" is recorded as the currency factor <i>rising</i>; a conditional
 * headline about a decision that has not been taken produces nothing; several outlets covering one
 * event produce one event; and the extractor reports no confidence figure at all rather than an
 * invented one.
 */
class MacroKeywordExtractorTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 9);

    private static MacroKeywordExtractor.Headline headline(long id, String title) {
        return new MacroKeywordExtractor.Headline(id, title, "", "Economic Times",
                "https://example.test/" + id, DAY);
    }

    private static List<MacroKeywordExtractor.Extracted> extract(String... titles) {
        List<MacroKeywordExtractor.Headline> hs = new java.util.ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            hs.add(headline(i + 1, titles[i]));
        }
        return MacroKeywordExtractor.extract(hs);
    }

    // ------------------------------------------------------------------ direction is the rule

    @Test
    @DisplayName("A columnist asking what a rate hike WOULD mean is not a rate hike")
    void opinionPieceIsNotAnEvent() {
        // Found on the first live run, not in review: this exact headline was filed as an RBI
        // rate rise that had not happened, and five holdings read a headwind off it. The words
        // in an opinion piece and in a report of the event are identical, so the only thing
        // separating them is the question the headline opens with.
        List<MacroKeywordExtractor.Extracted> out =
                extract("Why a rate hike could actually be bullish");

        assertThat(out).isEmpty();
        assertThat(MacroKeywordExtractor.touchesAnyFactor("Why a rate hike could actually be bullish"))
                .isFalse();
    }

    @Test
    @DisplayName("The opinion guard is anchored, so a report mentioning \"why\" still counts")
    void opinionGuardDoesNotSwallowReports() {
        // The asymmetry is deliberate (a missed event costs one ingest; an invented one files a
        // reading against hundreds of stocks that is never retracted) but it must not be so
        // greedy that ordinary reporting stops being read.
        List<MacroKeywordExtractor.Extracted> out =
                extract("RBI hikes repo rate by 25 bps - here is why it matters");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).factor()).isEqualTo(MacroFactor.INTEREST_RATES);
        assertThat(out.get(0).direction()).isEqualTo(MacroDirection.UP);
    }

    @Test
    @DisplayName("A repo rate hike is the interest-rate factor rising")
    void rateHikeIsUp() {
        List<MacroKeywordExtractor.Extracted> out = extract("RBI hikes repo rate by 25 bps");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).factor()).isEqualTo(MacroFactor.INTEREST_RATES);
        assertThat(out.get(0).direction()).isEqualTo(MacroDirection.UP);
    }

    @Test
    @DisplayName("A repo rate cut is the same factor falling")
    void rateCutIsDown() {
        List<MacroKeywordExtractor.Extracted> out = extract("RBI cuts repo rate to 5.75%");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).direction()).isEqualTo(MacroDirection.DOWN);
    }

    @Test
    @DisplayName("A falling rupee is the currency factor RISING - the whole point of the design")
    void weakerRupeeIsFactorUp() {
        List<MacroKeywordExtractor.Extracted> out = extract("Rupee slides to a record low against the dollar");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).factor()).isEqualTo(MacroFactor.USDINR);
        assertThat(out.get(0).direction())
                .as("USDINR rises when the rupee weakens; reading the word 'slides' as bearish is "
                        + "what made the deleted sentiment engines wrong for every exporter")
                .isEqualTo(MacroDirection.UP);
    }

    @Test
    @DisplayName("A recovering rupee is the same factor falling")
    void strongerRupeeIsFactorDown() {
        List<MacroKeywordExtractor.Extracted> out = extract("Rupee strengthens on strong foreign inflows");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).direction()).isEqualTo(MacroDirection.DOWN);
    }

    // ------------------------------------------------------------------ refusing the conditional

    @Test
    @DisplayName("A preview of a decision that has not been taken produces nothing")
    void conditionalHeadlineProducesNothing() {
        assertThat(extract("RBI expected to cut repo rate on Friday")).isEmpty();
        assertThat(extract("Ahead of the RBI policy meet: what to expect")).isEmpty();
        assertThat(extract("RBI keeps rates unchanged, rules out a cut this year")).isEmpty();
    }

    @Test
    @DisplayName("A conditional headline is not even stored by the scan")
    void conditionalDoesNotTouchAnyFactor() {
        assertThat(MacroKeywordExtractor.touchesAnyFactor(
                "rbi expected to cut repo rate on friday".toLowerCase())).isFalse();
    }

    @Test
    @DisplayName("An unrelated headline produces nothing and is not stored")
    void unrelatedHeadlineIgnored() {
        assertThat(extract("India win the third test by an innings")).isEmpty();
        assertThat(MacroKeywordExtractor.touchesAnyFactor("india win the third test")).isFalse();
    }

    // ------------------------------------------------------------------ one event, many outlets

    @Test
    @DisplayName("Four outlets reporting one rate cut produce one event carrying four headlines")
    void oneEventFromManyHeadlines() {
        List<MacroKeywordExtractor.Extracted> out = extract(
                "RBI cuts repo rate by 25 bps",
                "Repo rate cut: what it means for your home loan",
                "MPC cuts rates, signals more to come",
                "RBI cuts repo rate in a unanimous decision");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).headlineIds()).hasSize(4);
        assertThat(out.get(0).sourceUrls()).hasSize(4);
    }

    @Test
    @DisplayName("Two different factors on one day stay two events")
    void differentFactorsStaySeparate() {
        List<MacroKeywordExtractor.Extracted> out = extract(
                "RBI cuts repo rate by 25 bps",
                "Brent crude surges past $95 on supply fears");

        assertThat(out).hasSize(2);
        assertThat(out).extracting(MacroKeywordExtractor.Extracted::factor)
                .containsExactlyInAnyOrder(MacroFactor.INTEREST_RATES, MacroFactor.CRUDE_OIL);
    }

    // ------------------------------------------------------------------ the honest gaps

    @Test
    @DisplayName("The keyword extractor never reports a confidence: a match has no probability behind it")
    void confidenceIsAlwaysUnmeasured() {
        List<MacroKeywordExtractor.Extracted> out = extract("Brent crude surges past $95 on supply fears");

        assertThat(out.get(0).confidence())
                .as("a plausible-looking 0.4 here would be indistinguishable from a measured figure")
                .isNull();
    }

    @Test
    @DisplayName("An intensifier makes the move large, a hedge word makes it small")
    void magnitudeFollowsTheWords() {
        assertThat(extract("Brent crude surges past $95").get(0).magnitude())
                .isEqualTo(MacroMagnitude.LARGE);
        assertThat(extract("Brent crude edges higher in thin trade").get(0).magnitude())
                .isEqualTo(MacroMagnitude.SMALL);
        assertThat(extract("Brent crude rises on inventory data").get(0).magnitude())
                .isEqualTo(MacroMagnitude.MODERATE);
    }

    @Test
    @DisplayName("A policy decision is scheduled; a border incident is a surprise")
    void kindFollowsTheCalendar() {
        assertThat(extract("MPC cuts repo rate at its policy review").get(0).kind())
                .isEqualTo(MacroEventKind.SCHEDULED);
        assertThat(extract("Airspace closed after cross-border strike").get(0).kind())
                .isEqualTo(MacroEventKind.SURPRISE);
    }

    @Test
    @DisplayName("Geography is read from the headline rather than assumed to be India")
    void geographyIsRead() {
        assertThat(extract("Fed hikes rates as Powell turns hawkish").get(0).geography())
                .isEqualTo("United States");
        assertThat(extract("Chinese imports flood the domestic steel market").get(0).geography())
                .isEqualTo("China");
        assertThat(extract("RBI cuts repo rate by 25 bps").get(0).geography())
                .isEqualTo("India");
    }

    @Test
    @DisplayName("A monsoon shortfall and a good monsoon are the same factor in opposite directions")
    void monsoonBothWays() {
        assertThat(extract("IMD reports a monsoon deficit of 12% so far").get(0).direction())
                .isEqualTo(MacroDirection.UP);
        assertThat(extract("Above-normal monsoon revives sowing across the north").get(0).direction())
                .isEqualTo(MacroDirection.DOWN);
    }

    @Test
    @DisplayName("A tariff on Indian goods is recorded, and tariff relief is its opposite")
    void tariffsBothWays() {
        assertThat(extract("US imposes tariff on Indian steel exports").get(0).factor())
                .isEqualTo(MacroFactor.US_TARIFFS);
        assertThat(extract("US imposes tariff on Indian steel exports").get(0).direction())
                .isEqualTo(MacroDirection.UP);
        assertThat(extract("Washington announces tariff relief for Indian textiles").get(0).direction())
                .isEqualTo(MacroDirection.DOWN);
    }
}
