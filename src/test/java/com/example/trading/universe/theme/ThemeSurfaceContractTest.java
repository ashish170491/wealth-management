package com.example.trading.universe.theme;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.watchlist.WatchlistItemView;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract between the theme fields and {@code theme-cells.js} (SPEC §51.5).
 *
 * <p><b>Why this test exists.</b> There is no build step for the dashboard's JavaScript and
 * nothing type-checks the wire, so a rename on the Java side does not fail anywhere: the cell
 * finds {@code undefined}, {@code hasThemeAnswer} returns false, and the column draws the
 * unmeasured marker for ever — on stocks the app tagged perfectly well. That is the
 * {@code CompoundingSurfaceContractTest} failure shape, and it is quiet precisely because "not
 * measured" is an ordinary thing to see on this dashboard.
 *
 * <p>Four names, four surfaces, one renderer. If a name changes here it has to change in
 * {@code theme-cells.js} too, and this test is what makes that a compile-time-ish conversation
 * rather than a silent blank column.
 */
class ThemeSurfaceContractTest {

    /** Exactly what {@code theme-cells.js} dereferences off a row. */
    private static final List<String> WIRE_FIELDS =
            List.of("themes", "themeLabels", "themePolicies", "themeRoles");

    @Test
    @DisplayName("a holdings row carries all four names, as transient Lists")
    void holdingsRowCarriesTheContract() {
        for (String name : WIRE_FIELDS) {
            Field f = Arrays.stream(HoldingsEntity.class.getDeclaredFields())
                    .filter(x -> x.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "HoldingsEntity is missing '" + name + "', which theme-cells.js reads. "
                            + "The portfolio's Theme column will draw 'not measured' on every row."));

            assertThat(f.isAnnotationPresent(Transient.class))
                    .as("%s must be @Transient — it is computed on read, never stored", name)
                    .isTrue();
            assertThat(List.class.isAssignableFrom(f.getType()))
                    .as("%s must be a List: null means 'did not look' and an empty list means "
                        + "'looked, found no theme', and those must stay distinguishable", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a watchlist row carries the same four names")
    void watchlistRowCarriesTheContract() {
        List<String> components = Arrays.stream(WatchlistItemView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // Same names, not merely equivalent ones: the watchlist and the portfolio feed one
        // renderer, and a near-miss spelling blanks the column on exactly one screen (B-099).
        assertThat(components).containsAll(WIRE_FIELDS);

        for (RecordComponent rc : WatchlistItemView.class.getRecordComponents()) {
            if (WIRE_FIELDS.contains(rc.getName())) {
                assertThat(List.class.isAssignableFrom(rc.getType()))
                        .as("%s must be a List for the same null-vs-empty reason", rc.getName())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("the catalogue vocabulary contains no instruction to transact")
    void vocabularyIsBounded() {
        // SPEC §20 rule 10 as a test rather than a promise. The status words are rendered as
        // badges beside stock names, which is precisely where an instruction would be read as one.
        for (ThemeCatalog t : ThemeCatalog.values()) {
            assertThat(t.name()).doesNotContain("BUY").doesNotContain("SELL");
        }
        for (ThemeCoverage.Status s : ThemeCoverage.Status.values()) {
            assertThat(s.name()).doesNotContain("BUY").doesNotContain("SELL");
        }
    }

    @Test
    @DisplayName("no theme class exposes an actionable, bonus or weight field")
    void contributesZeroPointsIsCheckable() {
        // "Contributes zero points to any score" is asserted in several comments. This is the
        // version a future edit cannot quietly break: there is no switch to flip, so a theme
        // cannot become a scoring input without someone adding a field and failing this test
        // (the pattern EarningsSurfaceContractTest uses on EarningsConfig).
        for (Class<?> c : List.of(ThemeCatalog.class, ThemeCoverage.class, UniverseThemes.class,
                ThemeService.class, ThemeController.class)) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                assertThat(n)
                        .as("%s.%s looks like a scoring hook; SPEC §51.1 forbids one", c.getSimpleName(), f.getName())
                        .doesNotContain("actionable")
                        .doesNotContain("bonus")
                        .doesNotContain("weight")
                        .doesNotContain("points");
            }
        }
    }

    @Test
    @DisplayName("every populated theme has a short label for the narrow column")
    void everyThemeCanBeRenderedInAColumn() {
        // theme-cells.js keys THEME_SHORT by catalogue name and falls back to the raw enum. The
        // fallback is a safety net, not a plan: `AI_DATA_CENTRES` in a table cell is the kind of
        // thing that ships and stays. This fails the moment a theme is added without its label.
        List<String> shortLabelled = List.of(
                "SEMICONDUCTORS", "ELECTRONICS_EMS", "AI_DATA_CENTRES", "WATER_INFRASTRUCTURE",
                "DEFENCE_INDIGENISATION", "RAILWAY_MODERNISATION", "SOLAR_MANUFACTURING",
                "WIND_ENERGY", "GREEN_HYDROGEN", "POWER_TRANSMISSION", "EV_BATTERY", "PHARMA_API");

        for (ThemeCatalog t : UniverseThemes.populatedThemes()) {
            assertThat(shortLabelled)
                    .as("%s has no entry in THEME_SHORT in theme-cells.js — add one there and here",
                            t.name())
                    .contains(t.name());
        }
    }
}
