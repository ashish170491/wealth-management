package com.example.trading.valuation;

import com.example.trading.earnings.FiscalQuarter;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The fixed reference year and the arithmetic of "how far away is it" (SPEC §57). Pure.
 *
 * <p>The reference year is <b>FY2031</b>, the year ending 31 March 2031. It is fixed rather than
 * "five years from today" so that every row, on every date, answers the same question and the
 * stored record stays comparable across time (D10). It is a <i>reference year</i> for a
 * what-would-have-to-be-true table - never a date by which anything is expected to happen.
 */
public final class FiscalHorizon {

    private FiscalHorizon() {
    }

    /** The fiscal year the grid is stated for: the year ending 31 March 2031. */
    public static final int REFERENCE_FISCAL_YEAR = 2031;

    public static final LocalDate REFERENCE_YEAR_ENDS = LocalDate.of(2031, 3, 31);

    /** Under this many days left the table describes a stub, not a horizon. */
    public static final int MIN_RUNWAY_DAYS = 180;

    private static final double DAYS_PER_YEAR = 365.25;

    /** Whole days from the valuation date to the end of the reference year. */
    public static long runwayDays(LocalDate valuationDate) {
        return ChronoUnit.DAYS.between(valuationDate, REFERENCE_YEAR_ENDS);
    }

    /** {@code T}: years from the valuation date to 31 March 2031 (4.4627 for 2026-10-13). */
    public static double yearsFrom(LocalDate valuationDate) {
        return runwayDays(valuationDate) / DAYS_PER_YEAR;
    }

    /** {@code n}: the number of fiscal years of growth between the base year and the reference year. */
    public static int growthYears(int baseFiscalYear) {
        return REFERENCE_FISCAL_YEAR - baseFiscalYear;
    }

    /** False once fewer than {@link #MIN_RUNWAY_DAYS} remain (from 2030-10-02): the lens retires itself. */
    public static boolean hasRunway(LocalDate valuationDate) {
        return runwayDays(valuationDate) >= MIN_RUNWAY_DAYS;
    }

    /**
     * The oldest "latest fiscal year" a series may end in and still count as current on the date.
     *
     * <p>The year in progress on 13 Oct 2026 is FY2027, so the last completed one is FY2026. Annual
     * results are filed within 60 days (SEBI LODR Reg 33), so between April and June the newest
     * year may simply not have landed yet and the one before it is accepted. The relaxation applies
     * to April-June only: in January-March the year that ended last March has been public for
     * nine months, and accepting an older one there would call a stale series current.
     *
     * <p>The test is "at or after" this value, never "equal": a company that files early and
     * carries a newer year must not be refused as stale.
     */
    public static int requiredLastFiscalYear(LocalDate valuationDate) {
        int inProgress = FiscalQuarter.fiscalYear(valuationDate);
        int lastCompleted = inProgress - 1;
        int month = valuationDate.getMonthValue();
        boolean filingSeason = month >= 4 && month <= 6;
        return filingSeason ? lastCompleted - 1 : lastCompleted;
    }
}
