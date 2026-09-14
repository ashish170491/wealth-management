package com.example.trading.analyst;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a list of ledger rows into a per-house record (SPEC §49.6). Pure — no Spring, no I/O.
 *
 * <p><b>What it refuses to say is the point of it</b>, the same way the concall guidance ledger's
 * value is in its refusals (Gotcha 47):
 *
 * <ul>
 *   <li>A <b>pending</b> target is never counted as met or missed. Marking one missed before its
 *       date manufactures a bad record, and counting pending calls in the denominator would
 *       penalise a house for publishing often.</li>
 *   <li>A <b>superseded</b> target — revised before it resolved — is excluded from the hit rate
 *       and reported separately as a revision. It is not a miss, because the house withdrew it;
 *       it is also not free, because a house that revises down the week before a deadline would
 *       otherwise escape every miss it ever made. The revision rate is published beside the hit
 *       rate so that manoeuvre is visible rather than silently rewarded.</li>
 *   <li>Below {@code minResolved} the hit rate is <b>null</b> and the status is
 *       {@link Status#TOO_EARLY}. Two out of two is a hundred per cent, and that is precisely the
 *       number that misleads.</li>
 * </ul>
 *
 * <p><b>Excess return over the index is the honest column.</b> Whether a target was reached is
 * the analyst's own scoreboard and it flatters everyone in a rising market — the private-scoreboard
 * failure Gotcha 25 describes. The median excess return over the Nifty across the same dates is
 * what separates a call from the market it was made in, and it is the measure §23 already applies
 * to this app's own picks.
 */
public final class AnalystTrackRecord {

    private AnalystTrackRecord() {}

    public enum Status {
        /** Enough resolved calls to report a hit rate. */
        MEASURED,
        /** Calls on file, too few resolved to say anything. Not a bad record — no record. */
        TOO_EARLY
    }

    /**
     * One house's record, or the combined row when {@code brokerage} is {@code "All houses"}.
     *
     * @param hitRatePercent          reached / resolved; null below the floor
     * @param medianExcessReturnPct   median of (stock return − Nifty return) over resolved calls,
     *                                signed the way the call was made (see
     *                                {@link #inCallDirection}), so positive always means the house
     *                                was right; null below the floor or when nothing was measurable
     * @param medianDaysToReach       among reached calls only; null when none were reached
     * @param medianClaimedUpsidePct  what this house typically claims, measured at issue — context
     *                                for the hit rate, not a performance figure
     * @param revisionRatePercent     superseded / total; always reported, with its counts
     */
    public record HouseRecord(String brokerage,
                              Status status,
                              int total,
                              int resolved,
                              int reached,
                              int missed,
                              int pending,
                              int superseded,
                              int unpriced,
                              Double hitRatePercent,
                              Double medianExcessReturnPct,
                              Double medianDaysToReach,
                              Double medianClaimedUpsidePct,
                              Double revisionRatePercent) {
    }

    public static final String ALL_HOUSES = "All houses";

    /**
     * Build the per-house table plus a combined row, houses with the most resolved calls first.
     *
     * @param minResolved the floor below which a hit rate is withheld
     */
    public static List<HouseRecord> compute(Collection<AnalystTargetEntity> rows, int minResolved) {
        if (rows == null || rows.isEmpty()) return List.of();

        Map<String, List<AnalystTargetEntity>> byHouse = new TreeMap<>();
        for (AnalystTargetEntity r : rows) {
            if (r.getBrokerage() == null) continue;
            byHouse.computeIfAbsent(r.getBrokerage(), k -> new ArrayList<>()).add(r);
        }

        List<HouseRecord> out = new ArrayList<>();
        for (Map.Entry<String, List<AnalystTargetEntity>> e : byHouse.entrySet()) {
            out.add(record(e.getKey(), e.getValue(), minResolved));
        }
        out.sort((a, b) -> {
            int byResolved = Integer.compare(b.resolved(), a.resolved());
            return byResolved != 0 ? byResolved : Integer.compare(b.total(), a.total());
        });

        List<HouseRecord> withTotal = new ArrayList<>();
        withTotal.add(record(ALL_HOUSES, new ArrayList<>(rows), minResolved));
        withTotal.addAll(out);
        return withTotal;
    }

    static HouseRecord record(String house, List<AnalystTargetEntity> rows, int minResolved) {
        int reached = 0, missed = 0, pending = 0, superseded = 0, unpriced = 0;
        List<Double> excess = new ArrayList<>();
        List<Double> daysToReach = new ArrayList<>();
        List<Double> claimedUpside = new ArrayList<>();

        for (AnalystTargetEntity r : rows) {
            String status = r.getStatus() == null ? "" : r.getStatus();
            switch (status) {
                case "REACHED" -> {
                    reached++;
                    if (r.getDaysToReach() != null) daysToReach.add((double) r.getDaysToReach());
                }
                case "MISSED" -> missed++;
                case "SUPERSEDED" -> superseded++;
                case "UNPRICED" -> unpriced++;
                default -> pending++;
            }
            // Only a resolved call contributes to the return column. A pending call's return so
            // far is a partial observation of a different horizon, and averaging it in would make
            // a house look better or worse purely for having published recently.
            if (("REACHED".equals(status) || "MISSED".equals(status)) && r.getExcessReturnPct() != null) {
                excess.add(inCallDirection(r));
            }
            if (r.getUpsidePctAtCall() != null) claimedUpside.add(r.getUpsidePctAtCall());
        }

        int resolved = reached + missed;
        boolean enough = resolved >= minResolved;

        return new HouseRecord(
                house,
                enough ? Status.MEASURED : Status.TOO_EARLY,
                rows.size(),
                resolved,
                reached,
                missed,
                pending,
                superseded,
                unpriced,
                enough ? reached * 100.0 / resolved : null,
                enough ? median(excess) : null,
                median(daysToReach),
                median(claimedUpside),
                rows.isEmpty() ? null : superseded * 100.0 / rows.size());
    }

    /**
     * Excess return signed the way the call was made.
     *
     * <p>The row stores what the stock actually did against the index, which is one fact with one
     * meaning. But a house is right when a stock it told you to sell falls, so aggregating raw
     * excess across a mixed book would score its correct sell calls as failures and cancel them
     * against its correct buys. Here — and only here, at the point of aggregation — a {@code BELOW}
     * call's excess is negated so that positive always means "the call was right".
     *
     * <p>{@code UNKNOWN} direction cannot be signed and never reaches this method: such a row is
     * {@code UNPRICED} and is excluded upstream.
     */
    static double inCallDirection(AnalystTargetEntity r) {
        double raw = r.getExcessReturnPct();
        return "BELOW".equals(r.getDirection()) ? -raw : raw;
    }

    /** Median, or null for an empty list — never 0, which would read as a measured zero. */
    static Double median(List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * The sentence that must travel with every one of these tables.
     *
     * <p>The sample is not a census of analyst opinion. Since SPEC §49.11 most rows come from a
     * feed that indexes brokers' own research notes, with the headline reader as a fallback, so
     * the old four-filter caveat no longer describes it — and a caveat that describes a different
     * system from the one running is worse than none. What remains true, and is now said instead:
     * the feed carries the desks that publish into it rather than the whole market, a handful of
     * houses account for most of the rows, and the book is roughly four-fifths Buy.
     *
     * <p>The {@code revisionEffect} line is the one added from live data. Measured across 16
     * houses with 15+ resolved calls, the correlation between the share of a house's calls that
     * ever reach their deadline and its hit rate is <b>-0.57</b>: the two highest hit rates on
     * file (95.7%, 93.8%) belong to the two houses that resolve the fewest of their calls (4.3%,
     * 5.1%). A revised call is excluded from the hit rate because the house withdrew it, which is
     * right — but it means a frequently-revising desk's hit rate describes its update cadence,
     * not its accuracy, and the screen has to say so.
     */
    public static Map<String, Object> caveat(int minResolved) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("whatThisIs", "A record of published broker price targets and what the share price "
                + "actually did afterwards. Most rows come from a feed that indexes brokers' own "
                + "research notes; a few are read out of news headlines where that feed has no entry.");
        m.put("sampleBias", "This is not every broker in the market, and not every note each broker "
                + "writes. A handful of houses publish most of what is here, so the totals are not "
                + "a survey of Indian equity research — they describe the desks that publish into "
                + "this feed.");
        m.put("ratingMix", "Roughly four in five published targets are Buy and fewer than one in "
                + "twenty are Sell. In a rising market a buy-heavy book scores well on hit rate "
                + "whether or not the calls were good, which is why the excess-return column "
                + "matters more than the hit rate beside it.");
        m.put("revisionEffect", "Read the hit rate together with the revision rate. A house that "
                + "revises most of its targets before they fall due resolves only a small share of "
                + "its calls, and the few that do reach their deadline are the ones that were going "
                + "well — so a very high hit rate on a small resolved share is a fact about how "
                + "often that desk updates its view, not about how often it is right.");
        m.put("hitRateFloor", "A hit rate is withheld until a house has " + minResolved
                + " calls that have actually run their course. A record of two-from-two is not a record.");
        m.put("whyExcessReturn", "Reaching a target during a market-wide rally is not skill. The "
                + "excess-return column subtracts what the Nifty 50 did over exactly the same dates, "
                + "which is the same yardstick this app uses on its own picks.");
        m.put("notAdvice", "A target is one house's opinion. It is recorded here to be scored, "
                + "not to be followed, and it changes no score anywhere in this app.");
        return m;
    }
}
