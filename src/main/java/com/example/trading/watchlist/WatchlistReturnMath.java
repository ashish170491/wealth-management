package com.example.trading.watchlist;

/**
 * Return arithmetic for the watchlist. Pure; pinned by {@code WatchlistReturnMathTest}.
 *
 * <p>Every method returns {@code null} rather than {@code 0.0} when a leg is missing or a
 * "price" is the {@code <= 0} sentinel {@code MarketDataService} uses for "no price" (Gotcha 22).
 * A seeded row has no price-at-add, and its return must read "not measured", never "+0.0%".
 */
public final class WatchlistReturnMath {

    private WatchlistReturnMath() {}

    /** Percentage change from {@code from} to {@code to}; null unless both are real prices. */
    public static Double pctReturn(Double from, Double to) {
        if (from == null || to == null || from <= 0 || to <= 0) return null;
        return round1((to - from) / from * 100.0);
    }

    /** Stock return minus Nifty return, in percentage points; null when either is unmeasured. */
    public static Double excess(Double stockReturnPct, Double niftyReturnPct) {
        if (stockReturnPct == null || niftyReturnPct == null) return null;
        return round1(stockReturnPct - niftyReturnPct);
    }

    private static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
