package com.example.trading.funds;

/**
 * What kind of thing a scheme invests in.
 *
 * <p>Three states matter, not two (Gotcha 121). {@link #UNCLASSIFIED} means <em>this app has no
 * rule for the category AMFI printed</em> - a gap in the app, which counts against coverage and
 * is reported by name. It is never a finding about the scheme, and it must never render like one.
 */
public enum AssetClass {
    EQUITY,
    DEBT,
    HYBRID,
    GOLD,
    SOLUTION_ORIENTED,
    OTHER,
    /** No rule for this category. The app's gap - reported, never silently bucketed. */
    UNCLASSIFIED
}
