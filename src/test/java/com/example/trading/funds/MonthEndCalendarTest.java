package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §52.7 - which dates the month-end backfill is allowed to ask about.
 *
 * <p>Every case here is a date from the live archive, not an invented one (Gotcha 126a). The two
 * that matter were both found by running the endpoint rather than by reading its documentation.
 */
class MonthEndCalendarTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    @Test
    @DisplayName("an ordinary weekday month end is the first candidate")
    void weekdayMonthEndIsFirst() {
        // 31-Mar-2023 is a Friday and returns 7,269 rows with 1,691 equity schemes.
        List<LocalDate> c = MonthEndCalendar.candidates(YearMonth.of(2023, 3), TODAY);

        assertThat(c.get(0)).isEqualTo(LocalDate.of(2023, 3, 31));
    }

    @Test
    @DisplayName("a Saturday month end is never asked about - it is known to be nearly empty")
    void saturdayMonthEndIsSkippedEntirely() {
        // 30-Jun-2018 is a Saturday. Measured: 481 rows, 8 of them equity, against ~9,500 and
        // ~1,700 on the neighbouring weekday. Parsed as a real observation it says the entire
        // equity universe had no NAV that month (B-054). I walked into this twice while planning.
        List<LocalDate> c = MonthEndCalendar.candidates(YearMonth.of(2018, 6), TODAY);

        assertThat(c).doesNotContain(LocalDate.of(2018, 6, 30))
                .doesNotContain(LocalDate.of(2018, 6, 24)); // also a Sunday
        assertThat(c.get(0)).isEqualTo(LocalDate.of(2018, 6, 29)); // the Friday
        assertThat(c).allSatisfy(d -> assertThat(d.getDayOfWeek().getValue()).isLessThanOrEqualTo(5));
    }

    @Test
    @DisplayName("candidates walk backwards, so a holiday cluster is reachable without extra rules")
    void candidatesWalkBackwards() {
        // 30-Apr-2018 is a Monday; the previous business days are 27th (Fri), 26th, 25th, 24th.
        List<LocalDate> c = MonthEndCalendar.candidates(YearMonth.of(2018, 4), TODAY);

        assertThat(c).containsExactly(
                LocalDate.of(2018, 4, 30),
                LocalDate.of(2018, 4, 27),
                LocalDate.of(2018, 4, 26),
                LocalDate.of(2018, 4, 25),
                LocalDate.of(2018, 4, 24));
    }

    @Test
    @DisplayName("a future month yields nothing rather than a date the archive cannot answer")
    void futureMonthYieldsNothing() {
        assertThat(MonthEndCalendar.candidates(YearMonth.of(2026, 11), TODAY)).isEmpty();
    }

    @Test
    @DisplayName("the current month stops at today, never at a date that has not happened")
    void currentMonthStopsAtToday() {
        List<LocalDate> c = MonthEndCalendar.candidates(YearMonth.of(2026, 9), TODAY);

        // 20-Sep-2026 is a Sunday, so the newest candidate is the Friday before it.
        assertThat(c.get(0)).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(c).allSatisfy(d -> assertThat(d).isBeforeOrEqualTo(TODAY));
    }

    @Test
    @DisplayName("candidates never leave the month they belong to")
    void candidatesStayInsideTheirMonth() {
        // February 2021 begins on a Monday; a five-deep walk from the 28th (a Sunday) must not
        // reach into January, or a month would be filled with the previous month's NAVs.
        List<LocalDate> c = MonthEndCalendar.candidates(YearMonth.of(2021, 2), TODAY);

        assertThat(c).allSatisfy(d -> assertThat(YearMonth.from(d)).isEqualTo(YearMonth.of(2021, 2)));
    }

    @Test
    @DisplayName("monthsBetween is inclusive at both ends and refuses an inverted range")
    void monthsBetweenBounds() {
        assertThat(MonthEndCalendar.monthsBetween(YearMonth.of(2018, 4), YearMonth.of(2018, 6)))
                .containsExactly(YearMonth.of(2018, 4), YearMonth.of(2018, 5), YearMonth.of(2018, 6));
        assertThat(MonthEndCalendar.monthsBetween(YearMonth.of(2018, 6), YearMonth.of(2018, 4)))
                .isEmpty();
    }
}
