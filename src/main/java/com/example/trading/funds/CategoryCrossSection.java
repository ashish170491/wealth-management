package com.example.trading.funds;

import com.example.trading.funds.FundVocabulary.CategoryStanding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where one fund sat inside its own SEBI category over the same window (SPEC §52.8).
 *
 * <p><b>This is the primary reading, not the fallback.</b> Only two equity categories and one blend
 * have an index this app can honestly measure against (see
 * {@link FundVocabulary.BenchmarkAvailability}), and the index history on file reaches back about a
 * year against the three years any rolling figure needs. The category cross-section needs neither:
 * every peer's month-end series comes from the same AMFI archive as the subject's, over exactly the
 * same dates, so the comparison is like-for-like by construction.
 *
 * <p>Three rules make it honest, and each one exists because of a defect elsewhere in this codebase.
 *
 * <ol>
 *   <li><b>One plan and one option per scheme.</b> The peer set is Direct-plan Growth only. Without
 *       that, a fund appears in its own peer group two or four times at two or four different
 *       returns - a doubled population that shifts every percentile in it, while looking completely
 *       normal. This is the same arithmetic as B-096's three vocabularies for one sector.</li>
 *   <li><b>A category below the peer floor gets no ranking at all.</b> Measured on the live file,
 *       Contra has <b>four</b> schemes; a quartile over four is a number rather than a standing, and
 *       printing it would be the most confident thing on the screen. {@link #MIN_PEERS} is the
 *       refusal, and it is permanent for that category rather than a "not yet".</li>
 *   <li><b>A percentile is taken against the cross-section the fund actually joins</b>, never
 *       against whichever subset happened to be loaded. Ranking inside a partial batch produces a
 *       percentile in a different population wearing the same name - B-047's family, and the exact
 *       trap SPEC §12.13 records for the unscored-stock backfill.</li>
 * </ol>
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public record CategoryCrossSection(
        SebiCategory category,
        int peersMeasured,
        Double subjectReturnPct,
        Double medianReturnPct,
        Integer percentile,
        CategoryStanding standing,
        String reason
) {

    /**
     * Peers required before a standing is published.
     *
     * <p>Ten, set against the measured live cross-section rather than assumed: after the spelling
     * merge and the plan de-duplication the equity categories run Sectoral/Thematic 249, Flexi Cap
     * 47, Small Cap 36, ELSS 38, Large Cap 34, Mid Cap 34 - and <b>Contra 4</b>. Ten separates the
     * categories where a quartile means something from the one where it cannot.
     */
    public static final int MIN_PEERS = 10;

    /**
     * @param subject   the scheme being placed
     * @param returns   every scheme in the category with a comparable return over the same window,
     *                  including the subject
     */
    public static CategoryCrossSection of(SebiCategory category, long subject,
                                          Map<Long, Double> returns) {
        if (returns == null || !returns.containsKey(subject) || returns.get(subject) == null) {
            return new CategoryCrossSection(category, returns == null ? 0 : returns.size(),
                    null, null, null, CategoryStanding.NOT_MEASURED,
                    "this fund has no comparable return over the window, so it cannot be placed "
                            + "among its peers - which says nothing about how it did");
        }

        List<Double> peers = new ArrayList<>();
        for (Double v : returns.values()) {
            if (v != null && !v.isNaN()) {
                peers.add(v);
            }
        }
        peers.sort(Double::compareTo);

        double subjectReturn = returns.get(subject);

        if (peers.size() < MIN_PEERS) {
            return new CategoryCrossSection(category, peers.size(), subjectReturn, null, null,
                    CategoryStanding.TOO_FEW_PEERS,
                    "only " + peers.size() + " schemes in " + category.label() + " have a comparable "
                            + "record over this window, and a quartile drawn from that many is "
                            + "arithmetic rather than a standing. The fund's own return is shown "
                            + "instead, unranked");
        }

        // Share of peers this fund beat, 0-100. A fund tied with everything reads 0, which is
        // correct: it beat nobody.
        long beaten = peers.stream().filter(v -> v < subjectReturn).count();
        int percentile = (int) Math.round(100.0 * beaten / peers.size());
        double median = median(peers);

        CategoryStanding standing = percentile >= 75 ? CategoryStanding.UPPER_QUARTILE
                : percentile >= 50 ? CategoryStanding.UPPER_HALF
                : percentile >= 25 ? CategoryStanding.LOWER_HALF
                : CategoryStanding.LOWER_QUARTILE;

        String reason = String.format(
                "compounded %.2f%% a year against a %s median of %.2f%%, ahead of %d of %d schemes "
                        + "measured over exactly the same month ends. Every peer here is a "
                        + "Direct-plan Growth series, so no fund appears twice",
                subjectReturn, category.label(), median, beaten, peers.size());

        return new CategoryCrossSection(category, peers.size(), subjectReturn, median, percentile,
                standing, reason);
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
