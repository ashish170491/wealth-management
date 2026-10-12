package com.example.trading.funds;

/**
 * Whether one scheme's NAV series may be compared with its peers' at all (SPEC §52.8).
 *
 * <p><b>This is the units guard, and the live run is what proved it was needed.</b> The measurement
 * shipped, ran over the investor's real book, and computed a full rolling record and a category
 * percentile for <em>Motilal Oswal ELSS Tax Saver Fund Direct - IDCW Payout</em> against a peer set
 * of Direct-<b>Growth</b> series. Every number was arithmetically correct and the comparison was
 * meaningless.
 *
 * <p><b>Why an IDCW series cannot be compared with a Growth one.</b> Under the Income Distribution
 * cum Capital Withdrawal option the fund pays money out, and <em>the net asset value drops by the
 * amount paid</em>. The series therefore records the investor's return minus every distribution
 * ever made, while a Growth series records the whole thing. Their compound rates are two different
 * quantities that look identical on screen - B-047's family exactly (a quarter filed as a year),
 * B-060's (an RSI whose window could not reach its period) and B-120's (a per-share delta summed as
 * money). And the bias has a direction: the IDCW series always reads <b>worse</b>, so the failure is
 * a fund the investor owns being reported as a laggard when it is not.
 *
 * <p><b>An unresolved option is refused too, and that is the harder call.</b> It costs a real
 * measurement on a fund whose series is very likely fine. But "probably Growth" is exactly the
 * assumption that produces a confident wrong number, and this module's whole contract is that a
 * refusal is cheap and a false reading is not (Gotcha 68 - a default is not a statement).
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public record SeriesComparability(boolean comparable, String reason) {

    private static final SeriesComparability OK =
            new SeriesComparability(true, "Growth series - comparable with its Growth peers");

    /**
     * @param option the scheme's resolved NAV option
     */
    public static SeriesComparability of(SchemePlanKey.Option option) {
        if (option == SchemePlanKey.Option.GROWTH) {
            return OK;
        }
        if (option == SchemePlanKey.Option.IDCW) {
            return new SeriesComparability(false,
                    "this is an IDCW (income distribution) series: its net asset value falls by the "
                            + "amount of every payout, so its compound rate is the return after "
                            + "distributions while every peer here is measured before them. The two "
                            + "are different quantities and comparing them would understate this "
                            + "fund, so no record is published rather than a misleading one. The "
                            + "Growth series of the same scheme would be measurable");
        }
        return new SeriesComparability(false,
                "AMFI's file does not say whether this is a Growth or an income-distribution series, "
                        + "and the two are measured on different scales. Assuming Growth would "
                        + "produce a confident figure from an assumption, so it is left unmeasured "
                        + "and reported as such");
    }
}
