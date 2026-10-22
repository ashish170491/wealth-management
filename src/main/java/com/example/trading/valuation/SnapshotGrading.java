package com.example.trading.valuation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pre-registered grading rule VG-V1, as executable arithmetic (SPEC §57.6). Pure.
 *
 * <p><b>What this is, and is not.</b> It is the rule the stored rows will be graded by once filed
 * FY2027 results exist, written down now, before any outcome, so that the method cannot be
 * adjusted after seeing how it did. It is <b>not</b> the matured-results report: that computation
 * is a dated follow-up due by {@link #VALIDATION_DELIVERABLE_DUE} (the first FY2027 filings can land
 * from {@link #EARLIEST_GRADE_DATE}), and until then the only honest status is
 * {@code NO_MATURED_RESULTS}. Collecting snapshots is not validation.
 *
 * <p><b>One graded quantity for every row</b> that carries fit parameters - rows whose reliability
 * gates passed and rows where they were withheld alike. The gate is a cohort label, not a
 * precondition; whether it earns its keep is itself one of the questions the grading answers.
 *
 * <p><b>A loss is a counted outcome, never a dropped one.</b> The commonest way for a trend to be
 * wrong is for the business to stop earning, and a log error is undefined there. Dropping those
 * years would grade the method on the cases it handled best. {@link Outcome#ACTUAL_NON_POSITIVE}
 * is reported separately as the largest miss there is.
 *
 * <p><b>The honest ceiling.</b> At most five graded years exist (FY2027 to FY2031) and adjacent years
 * of one row share one fit, so the effective number of independent blocks is about one or two. The
 * only permitted statuses are no matured results, too early, and descriptive only: no p-value, no
 * "validated" and no promotion language is ever produced from this record (SPEC §38.10 asks for
 * twelve independent periods, which this record cannot reach by design).
 */
public final class SnapshotGrading {

    private SnapshotGrading() {
    }

    /** The follow-up that turns this record into a result is due before the first filing can land. */
    public static final LocalDate VALIDATION_DELIVERABLE_DUE = LocalDate.of(2027, 3, 31);

    /** FY2027 annual results begin to land about 9 April 2027 (measured lag after year end: 22-59 days). */
    public static final LocalDate EARLIEST_GRADE_DATE = LocalDate.of(2027, 4, 9);

    public enum Outcome { GRADED, ACTUAL_NON_POSITIVE, UNMATURED }

    /** {@code lnError} is ln(actual / graded value); present only when {@link Outcome#GRADED}. */
    public record Score(Outcome outcome, Double gradedValue, Double lnError) {
    }

    /**
     * {@code normalised_eps x (1 + c)^(Y - base FY)} with {@code 1 + c = exp(slope)}: the stored
     * parameters and nothing else. This is recomputed on demand and is never stored.
     */
    public static double gradedValue(double normalisedEps, double lnSlope, int baseFiscalYear, int gradedYear) {
        return normalisedEps * Math.exp(lnSlope * (gradedYear - baseFiscalYear));
    }

    /**
     * @param actualAdjustedEps  the first-seen actual for the graded year, on the row's share basis
     * @param actualAvailableFrom when that actual became public; null means it cannot be placed in
     *                            time, so it is unmatured and not graded
     */
    public static Score score(Double normalisedEps, Double lnSlope, Integer baseFiscalYear, int gradedYear,
                              Double actualAdjustedEps, LocalDate actualAvailableFrom) {
        if (normalisedEps == null || lnSlope == null || baseFiscalYear == null) {
            return new Score(Outcome.UNMATURED, null, null);
        }
        if (actualAdjustedEps == null || actualAvailableFrom == null) {
            return new Score(Outcome.UNMATURED, null, null);
        }
        double graded = gradedValue(normalisedEps, lnSlope, baseFiscalYear, gradedYear);
        if (actualAdjustedEps <= 0) {
            return new Score(Outcome.ACTUAL_NON_POSITIVE, graded, null);
        }
        return new Score(Outcome.GRADED, graded, Math.log(actualAdjustedEps / graded));
    }

    /**
     * The row that stands for each (symbol, base fiscal year, ruleset): the <b>earliest</b> written.
     * Later rows for the same unit exist (revisions) and are kept, but grading must not let a
     * method that was re-run after the fact mark its own homework.
     */
    public static List<ValuationGapSnapshotEntity> unitRows(List<ValuationGapSnapshotEntity> rows) {
        Map<String, ValuationGapSnapshotEntity> earliest = new LinkedHashMap<>();
        List<ValuationGapSnapshotEntity> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(ValuationGapSnapshotEntity::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (ValuationGapSnapshotEntity r : sorted) {
            if (r.getBaseFiscalYear() == null || r.getTrendLnSlope() == null) continue;   // nothing fitted
            String key = r.getSymbol() + "|" + r.getBaseFiscalYear() + "|" + r.getRuleset();
            earliest.putIfAbsent(key, r);
        }
        return new ArrayList<>(earliest.values());
    }
}
