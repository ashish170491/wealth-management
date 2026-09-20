package com.example.trading.funds;

import java.time.YearMonth;
import java.util.Set;

/**
 * How many schemes that once reported in a category no longer do (SPEC §52.9).
 *
 * <p><b>A measured number replacing a caveat.</b> AMFI's daily file lists only live schemes, so
 * every category median computed from it is a median over survivors - and SEBI's 2018 rule allowing
 * one scheme per category per fund house forced a merger wave <em>inside</em> the window this module
 * measures. The usual response is a sentence admitting survivorship bias exists. Once the month-end
 * archive is on file, the app can do better: it knows exactly which scheme codes were reporting in
 * a given month and are absent today.
 *
 * <p><b>The distinction that must not be lost.</b> A fund's <em>own</em> record is not
 * survivorship-biased - it is the record of a fund that is still here, measured over its own life.
 * Only the <em>peer comparison</em> is biased, because the peers that did worst were merged away and
 * their returns left the median with them. Conflating the two lets a real finding about a fund be
 * waved off with a general caveat, so the two are reported separately and this ledger attaches only
 * to the cross-section.
 *
 * <p>Note what a disappearance is <b>not</b>: a scheme absent from one month's file has not
 * necessarily wound up, which is why this reads across a span rather than a single month, and why
 * {@link MutualFundHistoryFetchEntity} exists - a month nobody fetched must never be read as a month
 * in which everything vanished (B-054).
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public record SurvivorshipLedger(
        SebiCategory category,
        YearMonth from,
        YearMonth to,
        int reportingAtStart,
        int stillReporting,
        int noLongerReporting,
        Double attritionPct,
        String reason,
        String caveat
) {

    public static final String PEER_CAVEAT =
            "Schemes that merged or wound up took their records out of this category's median with "
                    + "them, so the median of the survivors reads better than the choice an "
                    + "investor actually faced at the start of the window. This affects the peer "
                    + "comparison only: a fund's own record is the record of a fund that is still "
                    + "here, measured over its own life.";

    /**
     * @param atStart scheme codes reporting in the category in {@code from}
     * @param atEnd   scheme codes reporting in the category in {@code to}
     */
    public static SurvivorshipLedger between(SebiCategory category, YearMonth from, YearMonth to,
                                             Set<Long> atStart, Set<Long> atEnd) {
        if (atStart == null || atStart.isEmpty() || atEnd == null) {
            return new SurvivorshipLedger(category, from, to, 0, 0, 0, null,
                    "no month-end cross-section is on file for " + from + ", so attrition cannot be "
                            + "counted. That is a gap in this app's archive, not a finding that no "
                            + "scheme has ever closed",
                    PEER_CAVEAT);
        }

        int survived = (int) atStart.stream().filter(atEnd::contains).count();
        int gone = atStart.size() - survived;
        double attrition = 100.0 * gone / atStart.size();

        String reason = String.format(
                "of the %d %s schemes reporting in %s, %d no longer report in %s - %.1f%% of the "
                        + "category. Their records left the median with them",
                atStart.size(), category.label(), from, gone, to, attrition);

        return new SurvivorshipLedger(category, from, to, atStart.size(), survived, gone,
                attrition, reason, PEER_CAVEAT);
    }
}
