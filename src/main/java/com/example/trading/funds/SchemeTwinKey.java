package com.example.trading.funds;

import java.util.List;
import java.util.Locale;

/**
 * The key that pairs the Direct and Regular share classes of one scheme (SPEC §52.8).
 *
 * <p>AMFI gives the two plans <b>different scheme codes and different ISINs</b> - they are separate
 * rows with nothing linking them but their names. So the only available join is the name with every
 * plan and option word removed:
 *
 * <pre>
 * Axis Midcap Fund - Direct Plan - Growth Option   -&gt;  AXIS MIDCAP | AXIS MUTUAL FUND
 * Axis Midcap Fund - Regular Plan - Growth         -&gt;  AXIS MIDCAP | AXIS MUTUAL FUND
 * </pre>
 *
 * <p><b>The asymmetry that decides the design.</b> A missed pair costs one unmeasured commission,
 * reported honestly as {@code REGULAR_PLAN_NO_TWIN}. A <em>wrong</em> pair computes the gap between
 * two different portfolios and publishes it as a commission - a confident, specific and completely
 * fictional number. So the key is deliberately strict: the AMC name is part of it, and nothing is
 * inferred by fuzzy distance. That is the same direction as B-107's guard, where being over-strict
 * costs a missed event and being under-strict files a reading against hundreds of stocks on
 * something that never happened.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class SchemeTwinKey {

    private SchemeTwinKey() {}

    /**
     * Words that describe the share class rather than the portfolio.
     *
     * <p>Longest first, so {@code GROWTH OPTION} is removed before a bare {@code OPTION} could
     * leave the word {@code GROWTH} stranded in the key and split one scheme into two.
     */
    private static final List<String> PLAN_WORDS = List.of(
            "DIRECT PLAN", "REGULAR PLAN", "GROWTH OPTION", "IDCW OPTION",
            "DIVIDEND OPTION", "PAYOUT OPTION", "REINVESTMENT OPTION",
            "DIRECT", "REGULAR", "GROWTH", "IDCW", "DIVIDEND", "PAYOUT",
            "REINVESTMENT", "REINVEST", "OPTION", "PLAN");

    /**
     * @return a key shared by the two plans of one scheme, or null when either input is missing -
     *         <b>null, never a blank key</b>, because a blank key would silently pair every
     *         unnamed scheme with every other one
     */
    public static String of(String amcName, String schemeName) {
        if (amcName == null || amcName.isBlank() || schemeName == null || schemeName.isBlank()) {
            return null;
        }
        String core = strip(schemeName);
        if (core.isBlank()) {
            return null;
        }
        return core + " | " + normalise(amcName);
    }

    private static String strip(String schemeName) {
        String s = normalise(schemeName);
        for (String w : PLAN_WORDS) {
            s = s.replaceAll("\\b" + w + "\\b", " ");
        }
        // "Fund" and "Scheme" are noise that one plan sometimes carries and the other does not.
        s = s.replaceAll("\\bFUNDS?\\b", " ").replaceAll("\\bSCHEMES?\\b", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String normalise(String text) {
        return text.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9&]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
