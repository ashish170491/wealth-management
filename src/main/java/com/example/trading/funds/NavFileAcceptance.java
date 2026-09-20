package com.example.trading.funds;

/**
 * Whether one day's AMFI file is a usable month-end observation (SPEC §52.7).
 *
 * <p><b>The threshold is measured, not assumed</b> - the §12.11 / §49.12 discipline. Equity rows
 * across eight sampled archive dates spanning 2018-04 to 2026-08 read <b>1,687 / 1,707 / 1,707 /
 * 1,735 / 1,762 / 1,711 / 1,733 / 2,139 / 2,377</b> on days the equity universe published, and
 * <b>8</b> on the one Saturday sampled. The gap is two orders of magnitude wide and there is
 * nothing in it, so {@link #MIN_EQUITY_ROWS} sits in the middle of an empty band rather than on a
 * guess about how big the market ought to be.
 *
 * <p><b>Why equity rows and not total rows.</b> This was the first design and it was wrong.
 * {@code 30-Apr-2018} returns 3,271 rows against 9,553 the previous business day, which reads as a
 * broken file - but its equity rows are 1,687 against 1,707, so the equity universe published
 * normally and only debt schemes are missing. A total-row test would have rejected a good month and
 * silently shortened every fund's record by one observation, in a way no screen could show.
 *
 * <p><b>The rejection text carries no negated claim.</b> Its first draft ended {@code "...rather
 * than a day on which funds had no value"}, which is true and reads as its own opposite the moment
 * it is truncated in a table cell or skimmed in a log. That is Gotcha 53 in the direction of prose:
 * before writing a sentence that denies something alarming, check the alarming half cannot be read
 * alone. Its own test caught this before it shipped.
 *
 * <p><b>A rejected day is "not fetched", never "no NAV".</b> Those are different facts and the
 * second one, recorded, would say a fund ceased to exist - which is precisely the input the
 * survivorship ledger reads (SPEC §52.9). The caller records the rejection and tries the previous
 * business day.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public record NavFileAcceptance(int totalRows, int equityRows, boolean acceptable, String reason) {

    /**
     * Below this many equity rows, the equity universe did not publish that day.
     *
     * <p>See the class javadoc: the observed good-day minimum is 1,687 and the observed bad-day
     * maximum is 8. Any value in between separates them; 500 is chosen low enough that a genuinely
     * thin early-archive month is still accepted rather than being read as a market-wide silence.
     */
    public static final int MIN_EQUITY_ROWS = 500;

    public static NavFileAcceptance assess(int totalRows, int equityRows) {
        if (equityRows >= MIN_EQUITY_ROWS) {
            return new NavFileAcceptance(totalRows, equityRows, true,
                    equityRows + " equity schemes published");
        }
        return new NavFileAcceptance(totalRows, equityRows, false,
                "only " + equityRows + " equity schemes published (need " + MIN_EQUITY_ROWS
                        + ") - the equity universe did not strike a NAV on this date, so it is "
                        + "recorded as a date not worth recording, never as an observation");
    }
}
