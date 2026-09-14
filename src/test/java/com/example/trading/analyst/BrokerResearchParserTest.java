package com.example.trading.analyst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structured research feed reader (SPEC §49.11).
 *
 * <p><b>Every payload here is verbatim from the live feed</b>, for the reason recorded in
 * Gotcha 126(a): when this ledger's headline parser was written, every invented test case passed
 * and three of the first fourteen real rows were nonsense. A census over 800 real rows found four
 * different key sets, {@code scid} typed as both string and array, numbers arriving as String,
 * int and double in one field, and a rating of {@code "-"} — none of which an invented fixture
 * would have contained.
 */
class BrokerResearchParserTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verbatim, trimmed only of fields the reader does not touch. */
    private static final String REAL_ROW = """
            {"id":"14027806","organization":"Motilal Oswal","entry_date":"2026-09-11",
             "heading":"Buy Indraprastha Gas; target of Rs 195: Motilal Oswal",
             "attachment":"https://images.moneycontrol.com/x.pdf",
             "recommend_date":"September 10, 2026","target_price_date":"2026-09-10",
             "target_price":"195","recommended_price":153.45,"scid":"IG04",
             "exchange":"N","recommend_flag":"BUY",
             "stock_data":{"current":{"target_price":"195"},
                           "previous":{"target_price":193,"target_price_date":"14/08/2026"}}}
            """;

    @Test
    @DisplayName("A real row parses into every field the ledger needs")
    void realRow() {
        BrokerResearchRow r = BrokerResearchParser.parse(json(REAL_ROW));

        assertThat(r).isNotNull();
        assertThat(r.feedId()).isEqualTo(14027806L);
        assertThat(r.brokerage()).isEqualTo("Motilal Oswal");
        assertThat(r.scids()).containsExactly("IG04");
        assertThat(r.targetPrice()).isEqualTo(195.0);
        assertThat(r.brokerPrice()).isEqualTo(153.45);
        assertThat(r.calledOn()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(r.publishedOn()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(r.rating()).isEqualTo(AnalystTargetParser.Rating.BUY);
        assertThat(r.previousTarget()).isEqualTo(193.0);
        assertThat(r.researchPdfUrl()).endsWith(".pdf");
    }

    @Test
    @DisplayName("A call is filed from the day it became public, never from the note's own date")
    void effectiveDateNeverLeaksLookAhead() {
        // The note is dated the 10th and reached the feed on the 11th. Measuring from the 10th
        // would credit the house with whatever the price did while the note was still private -
        // a look-ahead that flatters the analyst, which is the one direction a track record must
        // never lean (the rule SPEC 32.6 introduced available_from for).
        BrokerResearchRow r = BrokerResearchParser.parse(json(REAL_ROW));
        assertThat(r.effectiveDate()).isEqualTo(LocalDate.of(2026, 9, 11));

        // And it degrades to whichever date exists rather than returning null.
        BrokerResearchRow onlyCalled = new BrokerResearchRow(1L, "X", java.util.List.of(), "h",
                10, null, LocalDate.of(2026, 1, 2), null,
                AnalystTargetParser.Rating.BUY, null, null, "N");
        assertThat(onlyCalled.effectiveDate()).isEqualTo(LocalDate.of(2026, 1, 2));
    }

    @Test
    @DisplayName("A dual-listed row carries both ids so the resolver can try each")
    void scidCanBeAList() {
        // Verbatim: NALCO publishes as ["NCI","NAC"], and only NAC resolves to an NSE listing -
        // NCI maps to a company called Ondeo Nalco. Taking the first blindly files the call
        // against the wrong company. These rows also drop stkname/stockShortName entirely.
        BrokerResearchRow r = BrokerResearchParser.parse(json("""
                {"id":"13994299","organization":"Motilal Oswal","entry_date":"2026-08-04",
                 "heading":"Neutral NALCO; target of Rs 380: Motilal Oswal",
                 "target_price_date":"2026-08-03","target_price":"380",
                 "scid":["NCI","NAC"],"recommend_flag":"HOLD"}
                """));

        assertThat(r).isNotNull();
        assertThat(r.scids()).containsExactly("NCI", "NAC");
        assertThat(r.rating()).isEqualTo(AnalystTargetParser.Rating.HOLD);
        assertThat(r.brokerPrice()).isNull();
        assertThat(r.previousTarget()).isNull();
    }

    @Test
    @DisplayName("A rating of '-' is an absence, not a Hold")
    void dashRatingIsNotStated() {
        // The feed writes "-" when a note carries a target but no stance (7 of 800 sampled rows).
        // Collapsing that into HOLD invents a neutral opinion for a house that expressed none -
        // the same error as reading a null score as 50 (Gotcha 21).
        assertThat(BrokerResearchParser.rating("-")).isEqualTo(AnalystTargetParser.Rating.NOT_STATED);
        assertThat(BrokerResearchParser.rating(null)).isEqualTo(AnalystTargetParser.Rating.NOT_STATED);
        assertThat(BrokerResearchParser.rating("Buy")).isEqualTo(AnalystTargetParser.Rating.BUY);
        assertThat(BrokerResearchParser.rating("REDUCE")).isEqualTo(AnalystTargetParser.Rating.SELL);
    }

    @Test
    @DisplayName("A number is read whether it arrives quoted, as an int, or as a double")
    void numbersAreCoerced() {
        assertThat(BrokerResearchParser.number(json("\"1,150.50\""))).isEqualTo(1150.50);
        assertThat(BrokerResearchParser.number(json("195"))).isEqualTo(195.0);
        assertThat(BrokerResearchParser.number(json("153.45"))).isEqualTo(153.45);
        assertThat(BrokerResearchParser.number(json("\"-\""))).isNull();
        assertThat(BrokerResearchParser.number(json("null"))).isNull();
    }

    @Test
    @DisplayName("An unparseable date is null, never today")
    void badDateIsNullNotToday() {
        // Filing an unreadable date as now would date a two-year-old call to this morning and
        // measure it over a window it never ran in - the rule the headline path already follows.
        assertThat(BrokerResearchParser.date(json("\"September 10, 2026\""))).isNull();
        assertThat(BrokerResearchParser.date(json("\"-\""))).isNull();
        assertThat(BrokerResearchParser.date(json("\"2026-09-10\""))).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    @DisplayName("A row with no usable target, house or date yields nothing rather than a guess")
    void refusals() {
        // No target
        assertThat(BrokerResearchParser.parse(json(
                "{\"id\":\"1\",\"organization\":\"X\",\"entry_date\":\"2026-01-01\"}"))).isNull();
        // No house
        assertThat(BrokerResearchParser.parse(json(
                "{\"id\":\"1\",\"target_price\":\"100\",\"entry_date\":\"2026-01-01\"}"))).isNull();
        // No date at all
        assertThat(BrokerResearchParser.parse(json(
                "{\"id\":\"1\",\"organization\":\"X\",\"target_price\":\"100\"}"))).isNull();
        // Out of bounds both ways - the same limits the headline parser uses
        assertThat(BrokerResearchParser.parse(json(
                "{\"organization\":\"X\",\"target_price\":\"0.4\",\"entry_date\":\"2026-01-01\"}"))).isNull();
        assertThat(BrokerResearchParser.parse(json(
                "{\"organization\":\"X\",\"target_price\":\"250000\",\"entry_date\":\"2026-01-01\"}"))).isNull();
        assertThat(BrokerResearchParser.parse(null)).isNull();
    }

    @Test
    @DisplayName("A revision is arithmetic, not a verb, and a rounding difference is not a revision")
    void actionFromTargets() {
        assertThat(BrokerResearchParser.parse(json(REAL_ROW)).action())
                .isEqualTo(AnalystTargetParser.Action.RAISE_TARGET);

        BrokerResearchRow cut = new BrokerResearchRow(1L, "X", java.util.List.of(), "h",
                100, null, null, LocalDate.of(2026, 1, 1),
                AnalystTargetParser.Rating.BUY, 120.0, null, "N");
        assertThat(cut.action()).isEqualTo(AnalystTargetParser.Action.CUT_TARGET);

        BrokerResearchRow same = new BrokerResearchRow(1L, "X", java.util.List.of(), "h",
                100, null, null, LocalDate.of(2026, 1, 1),
                AnalystTargetParser.Rating.BUY, 100.2, null, "N");
        assertThat(same.action()).isEqualTo(AnalystTargetParser.Action.MAINTAIN);

        // No prior target on file is NOT_STATED - not INITIATE, which would claim the feed said
        // something it did not (a first sighting here may be a house's tenth note on the stock).
        BrokerResearchRow first = new BrokerResearchRow(1L, "X", java.util.List.of(), "h",
                100, null, null, LocalDate.of(2026, 1, 1),
                AnalystTargetParser.Rating.BUY, null, null, "N");
        assertThat(first.action()).isEqualTo(AnalystTargetParser.Action.NOT_STATED);
    }

    @Test
    @DisplayName("A failed page and the end of the archive are different facts")
    void failureIsNotEndOfArchive() {
        // Found by running it. The first live backfill stopped at 34 of ~85 pages and reported
        // success: start=3300&limit=100 returned 400 (deterministically - 3200 and 3400 were fine
        // at the same size, and 3300 itself was fine at limit 50), fetchPage returned an empty
        // list, and the caller could not tell that from "no more rows". Fourteen months of
        // history were dropped silently. The WARN was already there; the control flow could not
        // act on it, which is B-054's rule one level up.
        BrokerResearchClient.Page failed = BrokerResearchClient.Page.failure();
        assertThat(failed.failed()).isTrue();
        assertThat(failed.rows()).isEmpty();
        assertThat(failed.endOfArchive())
                .as("a failed page must never be read as the end of the archive")
                .isFalse();

        BrokerResearchClient.Page end = BrokerResearchClient.Page.of(java.util.List.of());
        assertThat(end.failed()).isFalse();
        assertThat(end.endOfArchive()).isTrue();

        BrokerResearchClient.Page full = BrokerResearchClient.Page.of(
                java.util.List.of(BrokerResearchParser.parse(json(REAL_ROW))));
        assertThat(full.endOfArchive()).isFalse();
    }

    @Test
    @DisplayName("One house reported three ways collapses to one name")
    void houseNamesAreCanonicalised() {
        // The feed lists "Anand Rathi", "AnandRathi" and "Anand Rathi Financial Services" as
        // separate publishers. Unmerged, one firm's record splits across three rows of the
        // scoreboard, each below the five-call floor, so a house with a real record reports
        // TOO_EARLY for ever.
        assertThat(BrokerResearchCaptureService.canonicalHouse("Emkay Global Financial Services"))
                .isEqualTo(BrokerResearchCaptureService.canonicalHouse("Emkay Global"));
        assertThat(BrokerResearchCaptureService.canonicalHouse("  Motilal   Oswal "))
                .isEqualTo("Motilal Oswal");
        // A house the app does not know keeps its published name rather than being invented into
        // some canonical form.
        assertThat(BrokerResearchCaptureService.canonicalHouse("Some New Research LLP"))
                .isEqualTo("Some New Research LLP");
        assertThat(BrokerResearchCaptureService.canonicalHouse(null)).isNull();

        // Found on the first live scoreboard: the feed publishes "AnandRathi" and "Anand Rathi"
        // as separate houses, and a substring match cannot see through a missing space - so one
        // firm held two rows with different records (44.3% over 61 resolved beside 37.5% over 80),
        // which is the split this canonicalisation exists to prevent.
        assertThat(BrokerResearchCaptureService.canonicalHouse("AnandRathi"))
                .isEqualTo(BrokerResearchCaptureService.canonicalHouse("Anand Rathi"));
        assertThat(BrokerResearchCaptureService.canonicalHouse("ICICIdirect.com"))
                .isEqualTo("ICICI Direct");
        // The longest match still wins, so a shorter alias inside a fuller name cannot capture it.
        assertThat(BrokerResearchCaptureService.canonicalHouse("ICICI Securities Ltd"))
                .isEqualTo("ICICI Securities");
    }
}
