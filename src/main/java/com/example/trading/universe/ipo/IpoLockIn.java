package com.example.trading.universe.ipo;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The supply calendar of a new listing, and where in the post-IPO cycle it sits (SPEC §45.4).
 * Pure: takes dates, returns dates.
 *
 * <p><b>Why lock-ins are the first thing to look at after a listing.</b> On listing day the free
 * float is small and the buyers are the excited ones. Then, on a timetable written into SEBI's
 * rules, the sellers arrive: anchor investors may sell half their allotment after 30 days and the
 * rest after 90; every pre-IPO investor and the promoters' holding above their minimum are freed
 * after six months; the promoters' minimum 20% after eighteen. The six-month date is the one that
 * matters — it is when private-equity and venture holders who bought years earlier at a fraction
 * of the price can finally exit, and it is why SPEC §30.4 refuses to judge a listing before it.
 * Dates are measured from the listing date and stated as approximate: the rules count from
 * allotment, two or three days earlier.
 */
public final class IpoLockIn {

    private IpoLockIn() {
    }

    /** Anchor investors: half the allotment is freed after 30 days, the rest after 90 (ICDR, 2022 amendment). */
    public static final int ANCHOR_FIRST_HALF_DAYS = 30;
    public static final int ANCHOR_SECOND_HALF_DAYS = 90;
    /** Pre-IPO investors and promoter holding above the minimum contribution. */
    public static final int PRE_IPO_MONTHS = 6;
    /** The promoters' minimum 20% contribution. */
    public static final int PROMOTER_MINIMUM_MONTHS = 18;

    public record Milestone(String label, String who, LocalDate approxDate, boolean passed, long daysAway) {
    }

    public static List<Milestone> calendar(LocalDate listingDate, LocalDate today) {
        if (listingDate == null) return List.of();
        return List.of(
                milestone("Anchor investors may sell half", "Institutions allotted the day before the issue opened",
                        listingDate.plusDays(ANCHOR_FIRST_HALF_DAYS), today),
                milestone("Anchor investors may sell the rest", "The same institutions",
                        listingDate.plusDays(ANCHOR_SECOND_HALF_DAYS), today),
                milestone("Pre-IPO investors may sell", "Private-equity and venture holders who bought years earlier, "
                                + "and the promoters' holding above their minimum",
                        listingDate.plusMonths(PRE_IPO_MONTHS), today),
                milestone("Promoters' minimum stake unlocks", "The founders' required 20%",
                        listingDate.plusMonths(PROMOTER_MINIMUM_MONTHS), today));
    }

    private static Milestone milestone(String label, String who, LocalDate date, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, date);
        return new Milestone(label, who, date, days <= 0, days);
    }

    /** True once the six-month overhang has cleared — the earliest SPEC §30.4 will judge a listing. */
    public static boolean pastHypeWindow(LocalDate listingDate, LocalDate today, int maturityMonths) {
        if (listingDate == null) return false;
        return !listingDate.plusMonths(maturityMonths).isAfter(today);
    }

    /**
     * Where a listing sits in its cycle. Descriptive, not an instruction: it names the phase so
     * the reader knows which question to ask, and the quality columns beside it answer the other.
     */
    public enum Stage {
        /** Inside the first six months: overhang still ahead, deliberately not judged (SPEC §30.4). */
        HYPE_WINDOW,
        /** Past six months, still at or below its listing-day high: the sellers have not finished. */
        WASHOUT,
        /** Above its listing-day high but not yet building higher lows. */
        RECOVERING,
        /** Past six months, above the listing-day high, higher lows forming: the setup §30.4 surfaces. */
        BASE_FORMING,
        /** No usable price history from the broker. */
        NOT_MEASURED
    }

    public record StageRead(Stage stage, String reason) {
    }

    /**
     * @param aboveListingHigh null when there is no price history
     * @param baseFormed       null when there is too little history to judge a base
     */
    public static StageRead stage(LocalDate listingDate, LocalDate today, int maturityMonths,
                                  Boolean aboveListingHigh, Boolean baseFormed) {
        if (listingDate == null) return new StageRead(Stage.NOT_MEASURED, "No listing date on record.");
        if (!pastHypeWindow(listingDate, today, maturityMonths)) {
            long left = ChronoUnit.DAYS.between(today, listingDate.plusMonths(maturityMonths));
            return new StageRead(Stage.HYPE_WINDOW, String.format("Listed %d days ago. The six-month lock-in on "
                    + "pre-IPO holders clears in %d days; until then the app deliberately does not judge it.",
                    ChronoUnit.DAYS.between(listingDate, today), Math.max(0, left)));
        }
        if (aboveListingHigh == null) {
            return new StageRead(Stage.NOT_MEASURED, "Past the hype window, but the broker returned no usable "
                    + "price history for this symbol.");
        }
        if (!aboveListingHigh) {
            return new StageRead(Stage.WASHOUT, "Past the six-month lock-in but still below its listing-day high. "
                    + "The early sellers have not finished; nothing here says they have.");
        }
        if (baseFormed == null) {
            return new StageRead(Stage.RECOVERING, "Above its listing-day high, but too little trading history "
                    + "yet to tell whether it is building higher lows.");
        }
        if (!baseFormed) {
            return new StageRead(Stage.RECOVERING, "Above its listing-day high, but not yet making higher lows.");
        }
        return new StageRead(Stage.BASE_FORMING, "Past the lock-in, above the listing-day high, higher lows "
                + "forming: hype gone, early sellers finished, strength returning. Research it now.");
    }
}
