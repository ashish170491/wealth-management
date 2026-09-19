package com.example.trading.universe.theme;

import com.example.trading.multibagger.UniverseSectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the theme-map candidate pass (SPEC §51.10).
 *
 * <p>The property this class exists for is that a candidate is <b>evidence a person can reject</b>,
 * not a tag. Nothing here writes to the map, every row names the word that fired, and the scan
 * reports its own hit rate — because a suggestion screen that cannot say how much it missed lets
 * a short list read as "nothing new", which is the flattering silence the whole theme feature was
 * built to prevent (Gotcha 137).
 *
 * <p>Pure: no Spring context, no repository, no clock.
 */
class ThemeCandidatesTest {

    private static ThemeCandidates.Listing listing(String symbol, String name, String date) {
        return new ThemeCandidates.Listing(symbol, name, date == null ? null : LocalDate.parse(date));
    }

    private static UniverseSectors.Entry entry(String symbol, String industry, String name) {
        return new UniverseSectors.Entry(symbol, industry, name);
    }

    @Test
    @DisplayName("a business the map already names is never proposed again")
    void taggedIsNeverACandidate() {
        var scan = ThemeCandidates.scan(
                List.of(listing("ACMESOLAR", "ACME Solar Holdings Limited", "2024-11-13")),
                List.of(), Set.of("ACMESOLAR"), Set.of());

        assertThat(scan.candidates()).isEmpty();
        // But it was still scanned — the denominator must not shrink when a row is skipped, or the
        // hit rate silently improves every time the map grows.
        assertThat(scan.listingsScanned()).isEqualTo(1);
    }

    @Test
    @DisplayName("every candidate carries the word that fired, so it can be rejected at a glance")
    void evidenceTravelsWithTheCandidate() {
        var scan = ThemeCandidates.scan(
                List.of(listing("VIKRAMSOLAR", "Vikram Solar Limited", "2025-08-26")),
                List.of(), Set.of(), Set.of());

        assertThat(scan.candidates()).hasSize(1);
        var c = scan.candidates().get(0);
        assertThat(c.symbol()).isEqualTo("VIKRAMSOLAR");
        assertThat(c.matched()).isEqualTo("solar");
        assertThat(c.companyName()).isEqualTo("Vikram Solar Limited");
        assertThat(c.theme()).isEqualTo(ThemeCatalog.SOLAR_MANUFACTURING);
        assertThat(c.lane()).isEqualTo(ThemeCandidates.Lane.NEW_LISTING);
        assertThat(c.listingDate()).isEqualTo(LocalDate.parse("2025-08-26"));
    }

    @Test
    @DisplayName("a hint never fires on a word that merely starts the same way")
    void hintsRespectWordShape() {
        // Gotcha 53 in the positive direction: before matching a word, check what innocently
        // begins with it. Bare "wind" would file a pharma company under wind energy, which is why
        // the hint is "wind energy" and not "wind". This case is the reason that table is not the
        // obvious one-word-per-theme list.
        var scan = ThemeCandidates.scan(
                List.of(listing("WINDLAS", "Windlas Biotech Limited", "2021-08-16")),
                List.of(), Set.of(), Set.of());

        assertThat(scan.candidates()).isEmpty();

        // And the real thing still fires.
        var real = ThemeCandidates.scan(
                List.of(listing("SUZLON", "Suzlon Wind Energy Limited", "2024-01-01")),
                List.of(), Set.of(), Set.of());
        assertThat(real.candidates()).extracting(ThemeCandidates.Candidate::theme)
                .containsExactly(ThemeCatalog.WIND_ENERGY);
    }

    @Test
    @DisplayName("a theme with no diagnostic name is named, not silently empty")
    void themesWithoutAHintAreDeclared() {
        // A company called "...Pharma..." is a pharmaceutical company, which says nothing about
        // whether it makes active ingredients. Refusing to guess is right; refusing silently is
        // not, because a theme that can never produce a candidate looks exactly like a theme that
        // has none (Gotcha 44, Gotcha 121).
        assertThat(ThemeCandidates.themesWithoutHint())
                .contains(ThemeCatalog.PHARMA_API.label());
        assertThat(ThemeCandidates.hintsFor(ThemeCatalog.PHARMA_API)).isEmpty();

        var scan = ThemeCandidates.scan(List.of(), List.of(), Set.of(), Set.of());
        assertThat(scan.themesWithoutHint()).contains(ThemeCatalog.PHARMA_API.label());
    }

    @Test
    @DisplayName("nothing scanned and nothing found are different sentences")
    void emptyInputIsNotAnEmptyResult() {
        // B-054's rule: an empty collection returned on failure must not read as a finding. The
        // recall line is what keeps "the listings query returned nothing" distinguishable from
        // "246 listings, none matched".
        var nothingScanned = ThemeCandidates.scan(List.of(), List.of(), Set.of(), Set.of());
        assertThat(nothingScanned.listingsScanned()).isZero();
        assertThat(nothingScanned.recall()).contains("for want of input");

        var scannedNoneMatched = ThemeCandidates.scan(
                List.of(listing("ABC", "Some Trading Company Limited", "2025-01-01")),
                List.of(), Set.of(), Set.of());
        assertThat(scannedNoneMatched.candidates()).isEmpty();
        assertThat(scannedNoneMatched.recall()).contains("0 of 1");
        assertThat(scannedNoneMatched.recall()).doesNotContain("for want of input");
    }

    @Test
    @DisplayName("the recall figure is computed from the run, never asserted")
    void recallIsMeasured() {
        var scan = ThemeCandidates.scan(
                List.of(listing("A", "Alpha Solar Limited", "2025-01-01"),
                        listing("B", "Beta Textiles Limited", "2025-02-01"),
                        listing("C", "Gamma Aerospace Limited", "2025-03-01")),
                List.of(), Set.of(), Set.of());

        assertThat(scan.listingsScanned()).isEqualTo(3);
        assertThat(scan.listingsMatched()).isEqualTo(2);
        assertThat(scan.recall()).contains("2 of 3");
    }

    @Test
    @DisplayName("a business in two themes yields a row for each, and never a duplicate")
    void multiThemeYieldsOneRowPerTheme() {
        var scan = ThemeCandidates.scan(
                List.of(listing("X", "Orbit Aerospace and Rail Systems Limited", "2025-05-01")),
                // The same company also sits in the constituent list; it must not appear twice for
                // one theme just because two lanes saw it.
                List.of(entry("X", "Capital Goods", "Orbit Aerospace and Rail Systems Limited")),
                Set.of(), Set.of());

        assertThat(scan.candidates()).extracting(ThemeCandidates.Candidate::theme)
                .containsExactlyInAnyOrder(ThemeCatalog.DEFENCE_INDIGENISATION,
                        ThemeCatalog.RAILWAY_MODERNISATION);
        assertThat(scan.candidates()).extracting(ThemeCandidates.Candidate::lane)
                .containsOnly(ThemeCandidates.Lane.NEW_LISTING);
    }

    @Test
    @DisplayName("new listings sort ahead of constituents, newest first")
    void newListingsLeadTheList() {
        // The new-listing lane is the decay path the map cannot learn about any other way; a name
        // that has been listed for a decade has been missing from the map for a decade and is not
        // the urgent half.
        var scan = ThemeCandidates.scan(
                List.of(listing("OLD", "Older Solar Limited", "2024-01-01"),
                        listing("NEW", "Newer Solar Limited", "2026-01-01")),
                List.of(entry("CONST", "Power", "Constituent Solar Limited")),
                Set.of(), Set.of());

        assertThat(scan.candidates()).extracting(ThemeCandidates.Candidate::symbol)
                .containsExactly("NEW", "OLD", "CONST");
    }

    @Test
    @DisplayName("an exchange prefix never changes the answer")
    void prefixIsNotIdentity() {
        var scan = ThemeCandidates.scan(
                List.of(listing("NSE:ZZSOLAR", "Zed Solar Limited", "2025-01-01")),
                List.of(), Set.of("ZZSOLAR"), Set.of());
        // Already tagged under the bare symbol, so the prefixed listing must not be re-proposed.
        assertThat(scan.candidates()).isEmpty();
    }

    @Test
    @DisplayName("whether the universe already screens it is reported, because that is the cost")
    void screenedFlagIsCarried() {
        // Adding a name the app already screens costs nothing. Adding one it does not costs paced
        // broker calls on every run, for ever (Gotcha 22) - so the reader is told which it is.
        var scan = ThemeCandidates.scan(
                List.of(listing("IN", "Inside Solar Limited", "2025-01-01"),
                        listing("OUT", "Outside Solar Limited", "2025-01-01")),
                List.of(), Set.of(), Set.of("IN"));

        assertThat(scan.candidates()).filteredOn(c -> c.symbol().equals("IN"))
                .extracting(ThemeCandidates.Candidate::screened).containsExactly(true);
        assertThat(scan.candidates()).filteredOn(c -> c.symbol().equals("OUT"))
                .extracting(ThemeCandidates.Candidate::screened).containsExactly(false);
    }

    @Test
    @DisplayName("nothing here reads as an instruction to transact")
    void carriesNoInstructionToTransact() {
        // SPEC §19 and §20 rule 10. Same guard as the rest of §51, and word boundaries rather than
        // a bare substring: "whoever sells the servers" describes a business, and the first draft
        // of the sibling test failed on exactly that (Gotcha 53).
        var scan = ThemeCandidates.scan(
                List.of(listing("A", "Alpha Solar Limited", "2025-01-01")), List.of(),
                Set.of(), Set.of());
        String prose = (scan.caveat() + " " + scan.recall()).toLowerCase();

        for (String verb : List.of("buy", "sell", "accumulate", "exit", "invest")) {
            assertThat(prose).doesNotMatch("(?s).*\\b" + verb + "\\b.*");
        }
        assertThat(scan.caveat()).contains("questions, not tags");
    }
}
