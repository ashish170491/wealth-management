package com.example.trading.earnings;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * The Indian fiscal calendar, as a pure function of a quarter-end date (SPEC.md §50.2).
 *
 * <p>India's financial year runs April–March, so a quarter ending 30-Jun-2026 is <b>Q1 FY27</b>,
 * not Q2 2026. Getting this wrong is not cosmetic: a reader comparing "Q1" against last year's
 * "Q1" is comparing the same season, which is the entire point of a year-on-year read for a
 * business with a monsoon, a festive quarter or a March year-end push.
 *
 * <p>Pure: no clock, no I/O, no Spring.
 */
public final class FiscalQuarter {

    private FiscalQuarter() {
    }

    /**
     * {@code Q1 FY27} for a quarter ending 30-Jun-2026. Null in, null out.
     */
    public static String label(LocalDate quarterEnd) {
        if (quarterEnd == null) return null;
        return "Q" + number(quarterEnd) + " FY" + String.format("%02d", fiscalYear(quarterEnd) % 100);
    }

    /** 1 for Apr–Jun, 2 for Jul–Sep, 3 for Oct–Dec, 4 for Jan–Mar. */
    public static int number(LocalDate quarterEnd) {
        int m = quarterEnd.getMonthValue();
        // Shift so April becomes month 0, then take the quarter within the fiscal year.
        return ((m - 4 + 12) % 12) / 3 + 1;
    }

    /** FY2026-27 is returned as 2027 — the year the fiscal year ends in. */
    public static int fiscalYear(LocalDate quarterEnd) {
        return quarterEnd.getMonthValue() >= 4 ? quarterEnd.getYear() + 1 : quarterEnd.getYear();
    }

    /**
     * The quarter end three months on, snapped to the last day of its month.
     *
     * <p>The snap is load-bearing. Indian quarter ends are 30-Jun, 30-Sep, 31-Dec and 31-Mar, so a
     * plain {@code plusMonths(3)} from 30-Sep gives 30-Dec — a date no company has ever reported
     * for, which would then shift every expected-filing window by a day and make the estimate
     * look arbitrary.
     */
    public static LocalDate next(LocalDate quarterEnd) {
        if (quarterEnd == null) return null;
        return quarterEnd.plusMonths(3).with(TemporalAdjusters.lastDayOfMonth());
    }

    /**
     * The same quarter one year earlier — the only legitimate like-for-like comparison for a
     * seasonal business.
     */
    public static LocalDate yearBefore(LocalDate quarterEnd) {
        if (quarterEnd == null) return null;
        return quarterEnd.minusYears(1).with(TemporalAdjusters.lastDayOfMonth());
    }

    /**
     * True when two quarter ends are the same season one year apart, tolerating the few days by
     * which a filed quarter end can wobble (28-Feb vs 29-Feb, 30-Jun vs 1-Jul in a stray filing).
     */
    public static boolean isYearApart(LocalDate later, LocalDate earlier) {
        if (later == null || earlier == null) return false;
        long days = java.time.temporal.ChronoUnit.DAYS.between(earlier, later);
        return days >= 350 && days <= 380;
    }

    /** True when two quarter ends are consecutive quarters, with the same tolerance. */
    public static boolean isQuarterApart(LocalDate later, LocalDate earlier) {
        if (later == null || earlier == null) return false;
        long days = java.time.temporal.ChronoUnit.DAYS.between(earlier, later);
        return days >= 80 && days <= 100;
    }
}
