package com.example.trading.macro;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the look-ahead calendar (SPEC §48.5).
 *
 * <p>The properties that matter: the shipped file loads and every factor in it is real; a date that
 * has passed is not returned; a repeating row expands correctly inside the window and nowhere else;
 * and no row can carry an expected direction, because a dated prediction is still a prediction.
 */
class MacroCalendarTest {

    private static final String HEADER = "date,factor,label,kind,geography,recurrence,notes\n";

    @Test
    @DisplayName("The shipped calendar loads with every factor valid")
    void shippedCalendarLoads() {
        List<MacroCalendar.Entry> all = MacroCalendar.all();
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(e -> {
            assertThat(e.factor()).isNotNull();
            assertThat(e.label()).isNotBlank();
            assertThat(e.date()).isNotNull();
        });
    }

    @Test
    @DisplayName("A row carries no expected direction: the calendar says when, never which way")
    void calendarHasNoDirectionField() {
        for (java.lang.reflect.RecordComponent c : MacroCalendar.Entry.class.getRecordComponents()) {
            assertThat(c.getName().toLowerCase(java.util.Locale.ROOT))
                    .as("a direction or forecast field on a calendar row would be a dated prediction")
                    .doesNotContain("direction")
                    .doesNotContain("expected")
                    .doesNotContain("forecast");
        }
    }

    @Test
    @DisplayName("A dated event inside the window is returned with the days to go")
    void oneOffInsideWindow() {
        List<MacroCalendar.Entry> rows = MacroCalendar.parse(HEADER
                + "2026-09-20,INTEREST_RATES,RBI policy decision,SCHEDULED,India,ONCE,Watch the tone.\n");

        List<MacroCalendar.Occurrence> out =
                MacroCalendar.upcoming(rows, LocalDate.of(2026, 9, 10), 30);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).daysAway()).isEqualTo(10);
        assertThat(out.get(0).label()).isEqualTo("RBI policy decision");
    }

    @Test
    @DisplayName("A date that has passed is not returned - the ledger covers what has happened")
    void pastDatesExcluded() {
        List<MacroCalendar.Entry> rows = MacroCalendar.parse(HEADER
                + "2026-08-01,GOVT_CAPEX,Union Budget,SCHEDULED,India,ONCE,Gone.\n");

        assertThat(MacroCalendar.upcoming(rows, LocalDate.of(2026, 9, 10), 30)).isEmpty();
    }

    @Test
    @DisplayName("A date beyond the window is not returned either")
    void beyondWindowExcluded() {
        List<MacroCalendar.Entry> rows = MacroCalendar.parse(HEADER
                + "2026-12-25,INTEREST_RATES,RBI policy decision,SCHEDULED,India,ONCE,Far off.\n");

        assertThat(MacroCalendar.upcoming(rows, LocalDate.of(2026, 9, 10), 30)).isEmpty();
    }

    @Test
    @DisplayName("Today counts as upcoming, at zero days away")
    void todayIsIncluded() {
        List<MacroCalendar.Entry> rows = MacroCalendar.parse(HEADER
                + "2026-09-10,FOOD_INFLATION,India inflation figures,SCHEDULED,India,ONCE,Out today.\n");

        List<MacroCalendar.Occurrence> out =
                MacroCalendar.upcoming(rows, LocalDate.of(2026, 9, 10), 30);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).daysAway()).isZero();
    }

    @Test
    @DisplayName("A monthly row expands once per month inside the window")
    void monthlyExpands() {
        List<MacroCalendar.Entry> rows = MacroCalendar.parse(HEADER
                + "2026-01-12,FOOD_INFLATION,India inflation figures,SCHEDULED,India,MONTHLY,Twelfth of each month.\n");

        List<MacroCalendar.Occurrence> out =
                MacroCalendar.upcoming(rows, LocalDate.of(2026, 9, 10), 60);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).date()).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(out.get(1).date()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    @DisplayName("A monthly row never fires before its own first occurrence")
    void monthlyRespectsItsAnchor() {
        List<MacroCalendar.Entry> rows = MacroCalendar.parse(HEADER
                + "2026-11-01,GOVT_CAPEX,Monthly GST collections,SCHEDULED,India,MONTHLY,From November.\n");

        List<MacroCalendar.Occurrence> out =
                MacroCalendar.upcoming(rows, LocalDate.of(2026, 9, 10), 40);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("An annual row expands on its month and day")
    void annualExpands() {
        List<MacroCalendar.Entry> rows = MacroCalendar.parse(HEADER
                + "2026-02-01,GOVT_CAPEX,Union Budget,SCHEDULED,India,ANNUAL,Every first of February.\n");

        List<MacroCalendar.Occurrence> out =
                MacroCalendar.upcoming(rows, LocalDate.of(2027, 1, 20), 30);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).date()).isEqualTo(LocalDate.of(2027, 2, 1));
    }

    @Test
    @DisplayName("Occurrences come back soonest first")
    void sortedByDate() {
        List<MacroCalendar.Entry> rows = MacroCalendar.parse(HEADER
                + "2026-09-25,US_RATES,US Fed rate decision,SCHEDULED,United States,ONCE,Later.\n"
                + "2026-09-15,INTEREST_RATES,RBI policy decision,SCHEDULED,India,ONCE,Sooner.\n");

        List<MacroCalendar.Occurrence> out =
                MacroCalendar.upcoming(rows, LocalDate.of(2026, 9, 10), 30);

        assertThat(out).extracting(MacroCalendar.Occurrence::date)
                .containsExactly(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 25));
    }

    @Test
    @DisplayName("An unknown factor stops the load and names the line")
    void unknownFactorRefused() {
        assertThatThrownBy(() -> MacroCalendar.parse(HEADER
                + "2026-09-20,ELECTION_RESULT,State election,SCHEDULED,India,ONCE,Not a factor yet.\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("unknown factor");
    }

    @Test
    @DisplayName("A malformed date stops the load rather than silently dropping the row")
    void badDateRefused() {
        assertThatThrownBy(() -> MacroCalendar.parse(HEADER
                + "20 Sep 2026,INTEREST_RATES,RBI policy decision,SCHEDULED,India,ONCE,Wrong format.\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }
}
