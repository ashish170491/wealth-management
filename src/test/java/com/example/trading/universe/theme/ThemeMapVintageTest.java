package com.example.trading.universe.theme;

import com.example.trading.integrity.DataHealth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the theme map's vintage stamp and the check built on it (SPEC §51.9).
 *
 * <p><b>What this is guarding.</b> Nothing in this app can update {@code universe-themes.csv} —
 * no feed publishes it, no scheduler touches it, no code writes it — so it is exactly as current
 * as the last person to edit it. The failure that creates is quiet and flattering: coverage is
 * counted against the map's own denominator, so a map that stops growing while the market does not
 * goes on reporting <i>high</i> coverage of a shrinking list rather than a falling number. The
 * instrument built to find a blind spot becomes one. That is Gotcha 106(b) — a known cause must
 * expire with its fix — applied to a file rather than to an exception list.
 */
class ThemeMapVintageTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 19);

    @Test
    @DisplayName("the shipped map carries a readable review date and policy vintage")
    void shippedMapIsStamped() {
        // Asserted against the real file, not a fixture: a directive that stopped parsing would
        // leave the check reporting "no review date" for ever, which is a WATCH nobody could
        // clear by doing the thing it asks for.
        assertThat(UniverseThemes.reviewedOn())
                .as("universe-themes.csv must carry a # REVIEWED: ISO date")
                .isNotNull();
        assertThat(UniverseThemes.policyAsOf())
                .as("universe-themes.csv must say which budget its rows were written against")
                .isNotBlank();
    }

    @Test
    @DisplayName("an unreadable vintage is unknown, never fresh")
    void missingDateIsWatchNotOk() {
        // Null must not be read as "reviewed today" — that is the one answer that silently removes
        // the reminder the directive exists for (Gotcha 100, Gotcha 130: a date we cannot read
        // never defaults to now).
        DataHealth.Finding f = DataHealth.themeMapVintage(null, TODAY);

        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.headline()).containsIgnoringCase("no review date");
        assertThat(f.figure()).isNotBlank();
    }

    @Test
    @DisplayName("a map read this quarter passes, and says so with its date")
    void recentReviewIsOk() {
        DataHealth.Finding f = DataHealth.themeMapVintage(TODAY.minusDays(30), TODAY);

        assertThat(f.severity()).isEqualTo(DataHealth.Severity.OK);
        assertThat(f.figure()).contains("30 days ago");
        // Every finding carries the number it was reached on, so the reader can disagree with it.
        assertThat(f.figure()).contains(TODAY.minusDays(30).toString());
    }

    @Test
    @DisplayName("past the budget cycle it asks to be re-read, and says which way it fails")
    void staleMapAsksToBeReRead() {
        DataHealth.Finding f = DataHealth.themeMapVintage(TODAY.minusDays(200), TODAY);

        assertThat(f.severity()).isEqualTo(DataHealth.Severity.WATCH);
        assertThat(f.headline()).contains("200");
        // The detail must name the direction of the failure. "Old file" is not actionable; "it
        // keeps reporting high coverage of a list that stopped growing" is.
        assertThat(f.detail()).contains("high coverage");
        assertThat(f.detail()).containsIgnoringCase("budget");
    }

    @Test
    @DisplayName("the vintage check can never reach PROBLEM")
    void neverEscalatesToProblem() {
        // Gotcha 125. Nothing schedules this file, so it cannot be late, and amber that meant "a
        // job failed" would be a false alarm on the one channel whose entire job is to be
        // believed. Ten years stale is still a WATCH.
        List<LocalDate> ages = List.of(TODAY, TODAY.minusDays(181), TODAY.minusYears(10));
        for (LocalDate reviewed : ages) {
            assertThat(DataHealth.themeMapVintage(reviewed, TODAY).severity())
                    .as("reviewed %s must never be a PROBLEM", reviewed)
                    .isNotEqualTo(DataHealth.Severity.PROBLEM);
        }
        assertThat(DataHealth.themeMapVintage(null, TODAY).severity())
                .isNotEqualTo(DataHealth.Severity.PROBLEM);
    }

    @Test
    @DisplayName("the boundary is the budget cycle, not a round number of months")
    void boundaryIsSixMonths() {
        // 180 days straddles 1 February from either side of the year, which is the cycle that
        // actually ages the rows: every one of them cites a scheme whose outlay moves with it.
        assertThat(DataHealth.themeMapVintage(TODAY.minusDays(180), TODAY).severity())
                .isEqualTo(DataHealth.Severity.OK);
        assertThat(DataHealth.themeMapVintage(TODAY.minusDays(181), TODAY).severity())
                .isEqualTo(DataHealth.Severity.WATCH);
    }
}
