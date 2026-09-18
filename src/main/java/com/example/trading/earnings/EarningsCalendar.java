package com.example.trading.earnings;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "When is the next result due?" — derived from the company's own filing record (SPEC.md §50.4).
 *
 * <h2>What this can and cannot know</h2>
 * The board-meeting date on which a company will approve its results is announced by the company
 * a few days beforehand, and this app does not read that feed. So this does <b>not</b> return a
 * date. It returns a <b>window</b>, built from two things it does know: the quarter end (fixed by
 * the calendar) and how long this particular company has historically taken to publish after one.
 * The far edge is SEBI LODR Reg 33(3) — 45 days for a quarter, 60 for the audited March year-end.
 *
 * <p>A window is the honest shape of this answer. Quoting a single date would be inventing
 * precision nobody has, which is the failure SPEC §21 rule 7 exists to prevent, and it is exactly
 * what B-119 caught elsewhere: a value measured on one scale reported on a finer one.
 *
 * <h2>PAST_DUE means one of two things, and it says so</h2>
 * When the window has closed and no result is on file, that is either a company that has not
 * filed or a capture this app has not run. Those are different facts — one is about the business,
 * the other about the app — and collapsing them would let a gap in the app's own coverage read as
 * a red flag against the company (Gotcha 44, 121). The status carries both readings in words.
 *
 * <p>Pure: the caller supplies "today", so there is no clock here and the behaviour is testable.
 */
public final class EarningsCalendar {

    private EarningsCalendar() {
    }

    /** SEBI LODR Reg 33(3)(a): quarterly results within 45 days of the quarter end. */
    public static final int QUARTERLY_DEADLINE_DAYS = 45;
    /** Reg 33(3)(d): the audited March year-end gets 60. */
    public static final int ANNUAL_DEADLINE_DAYS = 60;

    /**
     * The earliest a large Indian company realistically reports, used only when this company has
     * no filing history of its own to measure. Opening the window earlier would have every stock
     * read "expected now" for six weeks a quarter, which is the same as saying nothing.
     */
    static final int EARLIEST_TYPICAL_LAG_DAYS = 25;

    /** Below this many observed lags there is no company-specific pattern, only the regulation. */
    static final int MIN_LAG_SAMPLES = 2;

    /** How far either side of its habit a company is allowed to land before the window is wrong. */
    static final int HABIT_MARGIN_DAYS = 7;

    /** Lags outside this range are a backfill artefact or a bad date, not a filing habit. */
    static final int MIN_SANE_LAG_DAYS = 5;
    static final int MAX_SANE_LAG_DAYS = 120;

    public enum Status {
        /** The quarter being reported on has not ended yet. */
        AWAITING_QUARTER_END,
        /** Inside the window in which this company usually publishes. */
        EXPECTED,
        /** Past the regulatory deadline with nothing on file — see the class note. */
        PAST_DUE,
        /** No filed quarter on record, so there is no anchor to count from. */
        NOT_MEASURED
    }

    /**
     * @param typicalLagDays this company's median days from quarter end to publication, null when
     *                       fewer than {@link #MIN_LAG_SAMPLES} filings have a real filed date
     * @param lagSamples     how many filings that median rests on
     * @param estimated      true when the window rests on the regulation rather than on this
     *                       company's own record — an assumption is never presented as a fact
     */
    public record Expectation(Status status,
                              LocalDate nextQuarterEnd,
                              String nextFiscalLabel,
                              LocalDate windowStart,
                              LocalDate windowEnd,
                              Integer typicalLagDays,
                              int lagSamples,
                              boolean estimated,
                              String text) {
    }

    public static Expectation notMeasured() {
        return new Expectation(Status.NOT_MEASURED, null, null, null, null, null, 0, true,
                "No quarterly filing is on record for this company, so there is nothing to count "
                        + "the next one from.");
    }

    /**
     * Work out when the quarter after the newest filed one is likely to be published.
     *
     * @param quarters every filed quarter for one company, in any order
     * @param today    the caller's date, so this stays pure
     */
    public static Expectation next(List<QuarterlyResultEntity> quarters, LocalDate today) {
        if (quarters == null || quarters.isEmpty() || today == null) return notMeasured();

        List<QuarterlyResultEntity> ordered = new ArrayList<>(quarters);
        ordered.removeIf(q -> q == null || q.getQuarterEnd() == null);
        if (ordered.isEmpty()) return notMeasured();
        ordered.sort((a, b) -> b.getQuarterEnd().compareTo(a.getQuarterEnd()));

        LocalDate latestEnd = ordered.get(0).getQuarterEnd();
        LocalDate nextEnd = FiscalQuarter.next(latestEnd);

        Integer median = medianLag(ordered);
        int deadline = nextEnd.getMonthValue() == 3 ? ANNUAL_DEADLINE_DAYS : QUARTERLY_DEADLINE_DAYS;

        // The window straddles the company's habit rather than starting on it. A company whose
        // median is 40 days does not file on day 40 every time, and a window opening exactly on
        // the median reads as "not due yet" right up to the morning it reports.
        //
        // The 25-day floor applies ONLY when there is no measured habit. Applying it to a
        // measured one is a defect the first live run caught: Infosys files around day 20, and
        // flooring its window at 25 opened it four days AFTER the company would already have
        // reported — an estimate contradicted by the very record it was built from. A measured
        // habit outranks a population default, which is the whole reason for measuring it.
        int startLag = median != null
                ? Math.max(MIN_SANE_LAG_DAYS, median - HABIT_MARGIN_DAYS)
                : EARLIEST_TYPICAL_LAG_DAYS;
        // The regulation is the far edge, unless this company habitually takes longer than it —
        // in which case its own record is the better guide and the window says so by being wider.
        int endLag = median != null ? median + HABIT_MARGIN_DAYS : startLag + HABIT_MARGIN_DAYS;
        LocalDate windowStart = nextEnd.plusDays(startLag);
        LocalDate windowEnd = nextEnd.plusDays(Math.max(deadline, endLag));

        Status status;
        if (today.isBefore(nextEnd)) {
            status = Status.AWAITING_QUARTER_END;
        } else if (!today.isAfter(windowEnd)) {
            status = Status.EXPECTED;
        } else {
            status = Status.PAST_DUE;
        }

        return new Expectation(status, nextEnd, FiscalQuarter.label(nextEnd),
                windowStart, windowEnd, median, lagSamples(ordered), median == null,
                text(status, nextEnd, windowStart, windowEnd, median, deadline));
    }

    /**
     * The company's median days from quarter end to publication.
     *
     * <p>Median rather than mean: one quarter delayed by an auditor dispute would drag a mean by
     * weeks and push every future window with it. Only <b>filed</b> dates count — an estimated
     * {@code availableFrom} is itself derived from the quarter end, so measuring a lag from it
     * would be measuring the app's own assumption and reporting it as the company's habit.
     */
    static Integer medianLag(List<QuarterlyResultEntity> quarters) {
        List<Long> lags = lags(quarters);
        if (lags.size() < MIN_LAG_SAMPLES) return null;
        Collections.sort(lags);
        int n = lags.size();
        long mid = n % 2 == 1 ? lags.get(n / 2) : (lags.get(n / 2 - 1) + lags.get(n / 2)) / 2;
        return (int) mid;
    }

    static int lagSamples(List<QuarterlyResultEntity> quarters) {
        return lags(quarters).size();
    }

    private static List<Long> lags(List<QuarterlyResultEntity> quarters) {
        List<Long> lags = new ArrayList<>();
        for (QuarterlyResultEntity q : quarters) {
            if (q == null || q.getQuarterEnd() == null || q.getAvailableFrom() == null) continue;
            if (Boolean.TRUE.equals(q.getAvailableFromEstimated())) continue;
            long days = ChronoUnit.DAYS.between(q.getQuarterEnd(), q.getAvailableFrom());
            if (days >= MIN_SANE_LAG_DAYS && days <= MAX_SANE_LAG_DAYS) lags.add(days);
        }
        return lags;
    }

    private static String text(Status status, LocalDate nextEnd, LocalDate start, LocalDate end,
                               Integer median, int deadline) {
        String basis = median != null
                ? "This company has taken about " + median + " days after a quarter end to publish."
                : "No filing pattern has been measured for this company yet, so the window is the "
                        + "regulatory one: SEBI allows " + deadline + " days.";
        // The caveat goes on every branch that quotes a window. Putting it only on one lets the
        // same range read as a firm date in the other, which is the precision this cannot claim.
        String caveat = " The app does not read board-meeting notices, so this is a window rather "
                + "than a date.";
        return switch (status) {
            case AWAITING_QUARTER_END -> "The quarter ends on " + nextEnd + ". Results usually "
                    + "follow between " + start + " and " + end + ". " + basis + caveat;
            case EXPECTED -> "The result for the quarter ended " + nextEnd + " is expected between "
                    + start + " and " + end + ". " + basis + caveat;
            case PAST_DUE -> "The result for the quarter ended " + nextEnd + " was due by " + end
                    + " and is not on file. That means either the company has not published it or "
                    + "this app has not captured it yet — the first is a fact about the business, "
                    + "the second about the app, and this cannot tell them apart.";
            case NOT_MEASURED -> "Nothing on record to count from.";
        };
    }
}
