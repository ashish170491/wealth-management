package com.example.trading.macro;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deciding when two reports are the same event (SPEC 48.2).
 *
 * <p><b>Why this is not the headline dedup.</b> Headlines are deduplicated by title, one row per
 * story. Events are a level above that: four outlets reporting an RBI rate cut on Wednesday and
 * two more running follow-ups on Thursday are <i>one</i> rate cut, and counting six would let the
 * loudest story in the news cycle dominate a portfolio reading purely by being repeated. Two
 * reports are the same event when they name the same factor moving the same way within a few days
 * of each other.
 *
 * <p><b>Merging keeps the earliest date and the strongest claim.</b> The earliest date because
 * that is when the event actually happened and the follow-up coverage is not a second occurrence;
 * the largest magnitude and highest confidence because the first wire copy is usually the thinnest
 * account of something the later ones describe properly. Headline references are unioned, so the
 * record shows every source that carried it.
 *
 * <p><b>The kind is never overwritten.</b> Whether an event was on the calendar is a fact about the
 * event, decided when it was first recognised; a later report is not evidence that a scheduled
 * rate decision was a surprise.
 */
public final class MacroEventDedup {

    private MacroEventDedup() {
    }

    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * The unique key a stored event carries: factor, direction and the day it happened.
     *
     * <p>Note this is deliberately <i>narrower</i> than {@link #sameEvent}. The key stops an exact
     * re-run from inserting twice even if the merge logic is bypassed; the window check is what
     * catches Wednesday's cut being reported again on Friday.
     */
    public static String key(MacroFactor factor, MacroDirection direction, LocalDate occurredAt) {
        return (factor == null ? "UNKNOWN" : factor.name())
                + "|" + (direction == null ? "?" : direction.name())
                + "|" + (occurredAt == null ? "?" : occurredAt.format(KEY_DATE));
    }

    /**
     * True when a candidate is a second report of an event already on the ledger.
     *
     * @param windowDays how many days apart two reports may be and still be one event; three is
     *                   the shipped default, which covers a weekend of follow-up coverage without
     *                   swallowing a genuine second move in the same week
     */
    public static boolean sameEvent(MacroFactor existingFactor, MacroDirection existingDirection,
                                    LocalDate existingDate, MacroFactor candidateFactor,
                                    MacroDirection candidateDirection, LocalDate candidateDate,
                                    int windowDays) {
        if (existingFactor == null || candidateFactor == null) return false;
        if (existingFactor != candidateFactor) return false;
        if (existingDirection != candidateDirection) return false;
        if (existingDate == null || candidateDate == null) return false;
        long apart = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(existingDate, candidateDate));
        return apart <= Math.max(0, windowDays);
    }

    /** The values a merge should end up with. Applied to the stored row by the caller. */
    public record Merged(LocalDate occurredAt, MacroMagnitude magnitude, Double confidence,
                         List<Long> headlineIds, List<String> sourceUrls, String summary) {
    }

    /**
     * Combine an existing event with a fresh report of it.
     *
     * <p>The summary is kept from whichever report carried the larger magnitude, because that is
     * the one that described the event rather than trailing it. On a tie the existing wording is
     * kept, so re-running an ingest does not churn the ledger.
     */
    public static Merged merge(LocalDate existingDate, MacroMagnitude existingMagnitude,
                               Double existingConfidence, List<Long> existingHeadlineIds,
                               List<String> existingUrls, String existingSummary,
                               LocalDate candidateDate, MacroMagnitude candidateMagnitude,
                               Double candidateConfidence, List<Long> candidateHeadlineIds,
                               List<String> candidateUrls, String candidateSummary) {

        LocalDate date = earliest(existingDate, candidateDate);
        MacroMagnitude magnitude = strongest(existingMagnitude, candidateMagnitude);
        Double confidence = higher(existingConfidence, candidateConfidence);

        Set<Long> ids = new LinkedHashSet<>();
        if (existingHeadlineIds != null) ids.addAll(existingHeadlineIds);
        if (candidateHeadlineIds != null) ids.addAll(candidateHeadlineIds);

        Set<String> urls = new LinkedHashSet<>();
        if (existingUrls != null) urls.addAll(existingUrls);
        if (candidateUrls != null) urls.addAll(candidateUrls);

        boolean candidateIsFuller = candidateMagnitude != null
                && (existingMagnitude == null || candidateMagnitude.rank() > existingMagnitude.rank());
        String summary = candidateIsFuller && candidateSummary != null && !candidateSummary.isBlank()
                ? candidateSummary
                : (existingSummary == null || existingSummary.isBlank() ? candidateSummary : existingSummary);

        return new Merged(date, magnitude, confidence, new ArrayList<>(ids), new ArrayList<>(urls), summary);
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static MacroMagnitude strongest(MacroMagnitude a, MacroMagnitude b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.rank() >= b.rank() ? a : b;
    }

    private static Double higher(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }
}
