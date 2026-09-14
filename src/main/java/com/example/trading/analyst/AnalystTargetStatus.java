package com.example.trading.analyst;

/**
 * What has become of one recorded target (SPEC §49.5).
 *
 * <p><b>The vocabulary contains no instruction to transact</b>, and {@code AnalystSurfaceContractTest}
 * fails if one is added. This is SPEC §20 rule 10: "is it a good time to buy" has exactly one
 * rule table in this app ({@code BuyTimingVerdict}, Gotcha 85), and a ledger of other people's
 * opinions is not allowed to become a second one. Every value below describes what happened to a
 * claim; none of them tells the investor to do anything.
 */
public enum AnalystTargetStatus {

    /**
     * Recorded, priced, and still running. Never counted as met or missed — marking a call missed
     * before its date manufactures a bad record (Gotcha 47).
     */
    PENDING,

    /** The share price touched the target at some point after the call. Records the first day it did. */
    REACHED,

    /** The horizon ran out without the price ever touching the target. */
    MISSED,

    /**
     * The same house published a new target on the same stock before this one resolved.
     *
     * <p>Excluded from the hit rate, because the house withdrew the claim — but counted and
     * published as a revision rate, because a house that revises the week before a deadline would
     * otherwise escape every miss it has ever made.
     */
    SUPERSEDED,

    /**
     * Recorded but not yet measurable: the closing price on the day of the call has not been
     * recovered, so there is no reference point for upside, direction or return.
     *
     * <p>An absence, not a zero. It is never counted in a hit rate, and the screens say
     * "not measured" rather than showing a 0% that would read as a call that went nowhere.
     */
    UNPRICED
}
