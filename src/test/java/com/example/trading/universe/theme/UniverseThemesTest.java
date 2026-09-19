package com.example.trading.universe.theme;

import com.example.trading.multibagger.UniverseSectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shipped theme map and its loader (SPEC §51.2).
 *
 * <p>The properties that matter: the file loads and is not quietly empty; every theme in the
 * catalogue that ships with rows is reachable; a business in two themes reads as two tags rather
 * than one; an exchange prefix never changes the answer; and a symbol the file does not name
 * returns an <b>empty</b> list rather than a null one — because empty means "checked, no theme"
 * and that is the ordinary answer for most of the market.
 *
 * <p>The verification split is asserted directly: a tagged symbol either resolves in
 * {@link UniverseSectors} or it does not, and the flag has to say which. That flag is the only
 * thing stopping an unconfirmed ticker being counted as a stock this app tracks.
 */
class UniverseThemesTest {

    @Test
    @DisplayName("the shipped file loads with real content, and is never quietly empty")
    void fileLoads() {
        // An empty map would make every screen report zero coverage across every theme, which
        // looks exactly like a feature nobody wired up rather than a file that failed to load.
        assertThat(UniverseThemes.rowCount()).isGreaterThan(50);
        assertThat(UniverseThemes.taggedSymbols()).hasSizeGreaterThan(50);
        assertThat(UniverseThemes.populatedThemes()).hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("the themes the investor asked about are all populated")
    void askedForThemesArePresent() {
        // The three that produced this feature. If a future edit empties one of these the map has
        // regressed to the state that made the question unanswerable.
        assertThat(UniverseThemes.membersOf(ThemeCatalog.SEMICONDUCTORS)).isNotEmpty();
        assertThat(UniverseThemes.membersOf(ThemeCatalog.WATER_INFRASTRUCTURE)).isNotEmpty();
        assertThat(UniverseThemes.membersOf(ThemeCatalog.AI_DATA_CENTRES)).isNotEmpty();
    }

    @Test
    @DisplayName("every populated theme carries a policy and a caution on every row")
    void everyRowNamesItsScheme() {
        for (ThemeCatalog theme : UniverseThemes.populatedThemes()) {
            assertThat(theme.policy()).isNotBlank();
            // The caution is the sentence that stops a theme list reading as a buy list. A blank
            // one would render as an empty paragraph and nobody would notice it had gone.
            assertThat(theme.caution()).isNotBlank();
            for (UniverseThemes.Tag t : UniverseThemes.membersOf(theme)) {
                assertThat(t.policy())
                        .as("%s/%s must name the scheme that puts it in the theme", theme, t.symbol())
                        .isNotBlank();
                assertThat(t.role())
                        .as("%s/%s must say where in the chain it sits", theme, t.symbol())
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("an untagged business returns an empty list, never null")
    void untaggedIsEmptyNotNull() {
        // The whole feature turns on this distinction downstream: empty means the map was
        // consulted and names nothing (a finding); a null would have to render as "not measured".
        assertThat(UniverseThemes.tagsFor("NSE:NOSUCHCOMPANYXYZ")).isNotNull().isEmpty();
        assertThat(UniverseThemes.themesFor("NSE:NOSUCHCOMPANYXYZ")).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("null and blank symbols are answered, not thrown at")
    void nullSymbolIsSafe() {
        assertThat(UniverseThemes.tagsFor(null)).isEmpty();
        assertThat(UniverseThemes.tagsFor("   ")).isEmpty();
        assertThat(UniverseThemes.themesFor(null)).isEmpty();
    }

    @Test
    @DisplayName("an exchange prefix never changes the answer")
    void prefixDoesNotChangeTheAnswer() {
        String bare = UniverseThemes.taggedSymbols().iterator().next();
        List<ThemeCatalog> plain = UniverseThemes.themesFor(bare);
        assertThat(plain).isNotEmpty();
        // A holding's prefix is not its identity (Gotcha 84): the same company must resolve
        // however the calling screen happens to spell it.
        assertThat(UniverseThemes.themesFor("NSE:" + bare)).isEqualTo(plain);
        assertThat(UniverseThemes.themesFor("BSE:" + bare)).isEqualTo(plain);
        assertThat(UniverseThemes.themesFor(bare.toLowerCase())).isEqualTo(plain);
    }

    @Test
    @DisplayName("a business in two themes keeps both, and themesFor deduplicates")
    void multipleThemesPerSymbol() {
        // KAYNES is deliberately in both semiconductors and electronics manufacturing: it is one
        // company doing two things, and flattening it to one theme would lose the reason it is
        // interesting in either.
        List<ThemeCatalog> kaynes = UniverseThemes.themesFor("KAYNES");
        assertThat(kaynes).contains(ThemeCatalog.SEMICONDUCTORS, ThemeCatalog.ELECTRONICS_EMS);
        assertThat(kaynes).doesNotHaveDuplicates();
        assertThat(UniverseThemes.tagsFor("KAYNES")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("the verified flag agrees with the sector table, symbol by symbol")
    void verifiedFlagIsTheSectorTable() {
        // This flag is the only thing keeping an unconfirmed ticker out of a coverage numerator.
        // If it ever drifts from its source the percentage silently starts overstating.
        for (String symbol : UniverseThemes.taggedSymbols()) {
            boolean known = UniverseSectors.entryFor(symbol) != null;
            for (UniverseThemes.Tag t : UniverseThemes.tagsFor(symbol)) {
                assertThat(t.verified())
                        .as("%s verified flag must match universe-sectors.csv", symbol)
                        .isEqualTo(known);
            }
        }
    }

    @Test
    @DisplayName("most of the map is confirmable, so the unverified bucket stays an exception")
    void mostSymbolsAreConfirmable() {
        Set<String> all = UniverseThemes.taggedSymbols();
        long verified = all.stream().filter(s -> UniverseSectors.entryFor(s) != null).count();
        // Not a precise figure — the point is that UNVERIFIED must remain a minority carve-out.
        // If most of the map stopped resolving, the coverage percentage would be computed over a
        // small unrepresentative denominator while still looking authoritative.
        assertThat(verified).isGreaterThan(all.size() / 2);
    }

    @Test
    @DisplayName("a theme token outside the catalogue is rejected rather than inventing a theme")
    void unknownThemeTokenIsRejected() {
        assertThat(ThemeCatalog.parse("NOT_A_REAL_THEME")).isEmpty();
        assertThat(ThemeCatalog.parse("")).isEmpty();
        assertThat(ThemeCatalog.parse(null)).isEmpty();
        // Case and surrounding space are tolerated; the name itself is not negotiable.
        assertThat(ThemeCatalog.parse(" semiconductors ")).contains(ThemeCatalog.SEMICONDUCTORS);
    }

    @Test
    @DisplayName("no theme label or caution contains an instruction to transact")
    void vocabularyCarriesNoInstruction() {
        // SPEC §19 / §20 rule 10. A theme is a coverage device; the moment its own wording says
        // "buy" it has become the signal engine this app twice deleted.
        //
        // WORD BOUNDARIES, not substrings — and the first draft of this test proved why. A bare
        // " sell" check failed on "whoever SELLS the servers", which is a description of what a
        // company does, not an instruction to anybody. That is Gotcha 53 exactly ("unqualified
        // opinion" contains "qualified opinion"): before matching a negative keyword, check what
        // innocently contains it.
        for (ThemeCatalog t : ThemeCatalog.values()) {
            String text = (t.label() + " " + t.policy() + " " + t.caution()).toLowerCase();
            for (String verb : List.of("buy", "sell", "accumulate", "exit")) {
                assertThat(text)
                        .as("%s wording must not instruct the reader to transact", t.name())
                        .doesNotMatch("(?s).*\b" + verb + "\b.*");
            }
            assertThat(text).doesNotContain("invest in");
        }
    }
}
