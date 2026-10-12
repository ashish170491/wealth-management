package com.example.trading.portfolio;

import java.util.List;

/**
 * What the allocation module needs to know about non-equity holdings (SPEC §5.2, §52.5).
 *
 * <p>A port, so {@code AllocationService} does not depend on the {@code funds} package. It is
 * injected as an {@code ObjectProvider} and the allocation module works with no implementation
 * present at all — the asset-class bucket then contains direct equity and cash only, which is a
 * true statement rather than a broken one.
 *
 * <p>Deliberately narrow. It exposes <b>value by asset class</b> and nothing else: no units, no
 * NAV, no scheme, no return. Anything richer would invite a caller to compute a fund's contribution
 * to single-stock concentration, and a fund's weight must never be squared into HHI — a mid-cap
 * fund at 20% of combined wealth is roughly sixty businesses, not one position.
 */
public interface FundAllocationSource {

    /**
     * One slice of non-equity money.
     *
     * @param assetClass coarse bucket, e.g. {@code EQUITY_FUND}, {@code DEBT_FUND}. <b>Null or
     *                   blank means the holding could not be classified</b> — reported by weight
     *                   and name as a coverage gap, never folded into a bucket called "Other"
     *                   (B-096, Gotcha 110).
     * @param value      current market value in rupees
     * @param label      what to call it when reporting an unclassified slice
     */
    record FundSlice(String assetClass, double value, String label) {}

    /** Every active non-equity holding whose value is known. Empty is a valid answer. */
    List<FundSlice> fundSlices();
}
