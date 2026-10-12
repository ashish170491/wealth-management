package com.example.trading.funds;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Which dates to ask AMFI for when the question is "what was this fund worth at the end of month X"
 * (SPEC §52.7).
 *
 * <p><b>Why this is a class and not a one-liner.</b> The obvious implementation asks for the
 * calendar month end. Measured against the live archive, that is wrong twice over:
 *
 * <ul>
 *   <li><b>A weekend month end returns an almost empty file.</b> {@code 30-Jun-2018} was a Saturday
 *       and returns <b>481 rows, 8 of them equity</b>, against ~9,500 and ~1,700 on the neighbouring
 *       weekday. Parsed naively that is not an error - it is a month in which the archive appears to
 *       say the entire equity universe has no NAV, which every downstream reading takes as fact
 *       (B-054). I walked into this twice while planning, which is why the rule lives here rather
 *       than in a caller's head.</li>
 *   <li><b>A weekday month end can still be short - and that is usually fine.</b> {@code 30-Apr-2018}
 *       is a Monday returning 3,271 rows against 9,553 the previous business day, which looks like
 *       the same failure. It is not: its <b>equity</b> rows are 1,687 against 1,707. The missing
 *       6,000 are debt schemes. Rejecting it on total rows would have discarded a perfectly good
 *       month, so the acceptance test is on equity rows alone - see
 *       {@link NavFileAcceptance}.</li>
 * </ul>
 *
 * <p>Weekends are skipped rather than attempted, because a request whose answer is already known is
 * a request not worth making against a third party's free endpoint.
 *
 * <p>This class is pure: no Spring, no repository, no clock of its own, no I/O.
 */
public final class MonthEndCalendar {

    private MonthEndCalendar() {}

    /**
     * How far back from the calendar month end a usable business day is looked for.
     *
     * <p>Five is enough for any run of weekend plus public holidays India actually has at a month
     * end (the longest observed is a Friday-to-Tuesday Diwali cluster), and small enough that a
     * month AMFI genuinely never published is reported as unfetched rather than chased for ever.
     */
    public static final int MAX_CANDIDATES = 5;

    /**
     * Business-day candidates for a month, latest first.
     *
     * @param month  the month whose end is wanted
     * @param today  the caller's today; a candidate after it is never returned, so a partially
     *               elapsed current month yields the last business day so far rather than a future
     *               date the archive cannot possibly answer
     * @return at most {@link #MAX_CANDIDATES} dates, newest first; empty when the whole month is in
     *         the future
     */
    public static List<LocalDate> candidates(YearMonth month, LocalDate today) {
        List<LocalDate> out = new ArrayList<>(MAX_CANDIDATES);
        LocalDate d = month.atEndOfMonth();
        if (today != null && d.isAfter(today)) {
            d = today;
        }
        while (out.size() < MAX_CANDIDATES && !d.isBefore(month.atDay(1))) {
            if (!isWeekend(d)) {
                out.add(d);
            }
            d = d.minusDays(1);
        }
        return out;
    }

    /** Every month from {@code from} to {@code to} inclusive, oldest first. */
    public static List<YearMonth> monthsBetween(YearMonth from, YearMonth to) {
        List<YearMonth> out = new ArrayList<>();
        if (from == null || to == null || from.isAfter(to)) {
            return out;
        }
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            out.add(m);
        }
        return out;
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
