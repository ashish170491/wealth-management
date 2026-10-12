package com.example.trading.funds;

import com.example.trading.learning.validation.Statistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Does a fund's past record tell you anything about its next one? (SPEC §52.13)
 *
 * <p>This class exists because the investor asked for a list of good funds to buy, and SPEC §52.11
 * refuses to produce one. Quoting a non-goal at someone is not an argument, so this measures the
 * claim on their own market instead: split the month-end archive into two non-overlapping windows,
 * rank every fund inside its own SEBI category in the first, and see where it landed in the second.
 * <b>Of the funds in the best quarter of their category in window A, what share were still in the
 * best quarter in window B?</b> If that is near 25% then a ranked "top funds" table is a list of
 * coin flips and the refusal is correct. If it is well above 25%, the non-goal deserves revisiting
 * and this is the evidence that would reopen it.
 *
 * <p>It is the fund-side sibling of §23's recommendation accuracy and §38.9's walk-forward harness:
 * the app measuring whether its own method works, rather than assuming it. It produces
 * <b>no verdict on any fund</b>, adds no score anywhere and names no scheme as worth owning.
 *
 * <h2>The four things that make it honest</h2>
 *
 * <ol>
 *   <li><b>Survivorship is counted, not caveated</b> (§52.9's rule with teeth). A fund that was top
 *       quartile in window A and then merged or wound up cannot appear in the transition matrix at
 *       all, because it has no window-B ranking. Dropping it silently inflates persistence, since
 *       the funds that vanish are overwhelmingly the ones that stopped working. So the dead are
 *       counted, and the headline is published as a <b>range</b>: the naive figure over survivors,
 *       and a lower bound that treats every vanished top-quartile fund as having failed. The truth
 *       is between them, and the honest reading is the lower one.</li>
 *   <li><b>One split is one observation.</b> Two windows give exactly <em>one</em> test of
 *       persistence, on one market, over one stretch of history that happened to contain a
 *       particular sequence of events. The fund count is not the sample size either - funds in a
 *       category hold heavily overlapping stocks, so forty large-cap funds are nothing like forty
 *       independent draws. <b>No p-value, no t-statistic and no significance language is produced
 *       anywhere in this class</b>, for the same reason {@link RollingWindowRecord} produces none
 *       (Gotcha 92, Gotcha 143). A binomial test over correlated funds would return a confident
 *       number that means nothing, which is worse than returning none.</li>
 *   <li><b>Sectoral and thematic funds are reported separately, never in the headline.</b> Ranking a
 *       pharma fund against a defence fund measures which sector ran, not which manager was good -
 *       and if the same sector leads in both windows the whole exercise reports skill that is
 *       really momentum in one industry. They are 265 of the 637 screenable schemes, so folding
 *       them in would let that one artefact dominate the answer.</li>
 *   <li><b>A category below the floor gets no quartile at all</b>, the same refusal and the same
 *       reason as {@link CategoryCrossSection#MIN_PEERS}: a quartile needs enough funds that each
 *       quarter holds more than a rounding error.</li>
 * </ol>
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class FundPersistence {

    private FundPersistence() {}

    /**
     * Funds a category needs, present in <b>both</b> windows, before its quartiles are published.
     *
     * <p>Twelve rather than {@link CategoryCrossSection#MIN_PEERS}'s ten, because this splits into
     * four groups rather than placing one fund: twelve puts three in each quarter, and below that a
     * single fund changing places moves the headline share by a third. Judgement, not a measured
     * percentile - the cross-section to calibrate against is the thing being measured.
     */
    public static final int MIN_FUNDS_PER_CATEGORY = 12;

    /** What a fund would score by chance alone. The whole question is the distance from this. */
    public static final double CHANCE = 0.25;

    /**
     * How far above chance the observed share must sit before each band is used.
     *
     * <p>These are judgement, set before the measurement was run so the bands could not be drawn
     * around whatever came back - the §38.8 rule about pre-registering a hypothesis, applied to a
     * single measurement. Stated on screen as judgement, the way §43.4 flags {@code REQUIRED_SHARE}.
     */
    public static final double FOUND_MARGIN_PP = 15.0;
    public static final double WEAK_MARGIN_PP = 5.0;

    /**
     * A band boundary must be coarser than the resolution of the data behind it.
     *
     * <p>The first live run measured 8 of 40 top-quartile funds holding their place - 20% against a
     * 25% null - and the fixed 5-point band called that {@code REVERSAL}, a directional claim about
     * manager skill, off a difference of <b>two funds</b>. With forty funds in the top quarter one
     * fund is worth 2.5 points, so a five-point band is two funds wide and the verdict is noise
     * wearing a name.
     *
     * <p>So the band floor is the larger of the fixed threshold and this many funds' worth of the
     * actual sample. Three, because one fund moving must never change the answer and two is still
     * within what a single merger or a single month's timing would shift. Same family as Gotcha
     * 88's fourth rule - a collapse threshold belongs to a scale - here applied to a percentage
     * whose scale is set by how many funds are in the top quarter.
     */
    public static final double RESOLUTION_MULTIPLE = 3.0;

    /**
     * Funds a category needs <b>in its top quarter</b> before a percentage is published for it.
     *
     * <p>Measured on the live archive, the per-category top quarters run 3 to 7 funds, so one fund
     * moving swings a category's share by 14 to 33 points - and the table then reads "Large & Mid
     * Cap 50%, Large Cap 0%" as though that were a finding about managers rather than four coin
     * flips. The counts are still shown ("1 of 7"), because a count cannot be over-read the way a
     * percentage can; the percentage is withheld. The pooled figure is the only one this archive
     * can support.
     */
    public static final int MIN_TOP_QUARTILE_FOR_SHARE = 8;

    /**
     * What the measurement says about <b>picking funds on past record</b> - never about a fund.
     *
     * <p>Deliberately clumsy words. Anything shorter ("good", "works") would be quoted back as a
     * verdict on a scheme, which is the one thing this class must never produce.
     */
    public enum PersistenceVerdict {
        PERSISTENCE_FOUND("Past ranking carried information"),
        WEAK_PERSISTENCE("Past ranking carried a little information"),
        NO_BETTER_THAN_CHANCE("Past ranking told you nothing"),
        REVERSAL("Past winners did worse than chance"),
        NOT_MEASURED("Not measured");

        private final String label;

        PersistenceVerdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One fund's two readings.
     *
     * @param cagrA null when the fund had no complete series across window A - it did not exist yet
     * @param cagrB null when it has no complete series across window B, which for a fund that had
     *              one in A means it <b>stopped reporting</b>: merged or wound up
     */
    public record Entry(long schemeCode, String schemeName, Double cagrA, Double cagrB) {}

    /** What happened inside one category. */
    public record CategoryResult(
            SebiCategory category,
            String categoryLabel,
            int fundsInBothWindows,
            int topQuartileA,
            int stayedTopQuartile,
            int fellToBottomQuartile,
            Double shareStayedTopPct,
            Double rankCorrelation,
            int vanishedAfterA,
            int vanishedAfterATopQuartile,
            boolean inHeadline,
            String reason
    ) {}

    /** The whole reading. */
    public record Result(
            List<CategoryResult> categories,
            int categoriesMeasured,
            int fundsInBothWindows,
            int topQuartileA,
            int stayedTopQuartile,
            int fellToBottomQuartile,
            Double shareStayedTopPct,
            Double shareStayedTopLowerBoundPct,
            /** What one fund is worth, in percentage points. The resolution of the headline. */
            Double onePerFundPp,
            /** How far from chance the share must sit before a direction is claimed. */
            Double bandFloorPp,
            Double rankCorrelation,
            int vanishedAfterA,
            int vanishedAfterATopQuartile,
            PersistenceVerdict verdict,
            String headline,
            List<String> caveats
    ) {}

    /**
     * Measure persistence across every supplied category.
     *
     * @param byCategory funds grouped by their SEBI category, each entry carrying both window
     *                   returns (either may be null)
     */
    public static Result measure(Map<SebiCategory, List<Entry>> byCategory) {
        List<CategoryResult> results = new ArrayList<>();
        int totalBoth = 0;
        int totalTopA = 0;
        int totalStayed = 0;
        int totalFell = 0;
        int totalVanished = 0;
        int totalVanishedTop = 0;
        List<double[]> pooledRanks = new ArrayList<>();

        List<SebiCategory> ordered = new ArrayList<>(byCategory.keySet());
        ordered.sort(Comparator.comparing(Enum::name));

        for (SebiCategory category : ordered) {
            CategoryResult r = measureCategory(category, byCategory.get(category), pooledRanks);
            results.add(r);
            if (!r.inHeadline()) {
                continue;
            }
            totalBoth += r.fundsInBothWindows();
            totalTopA += r.topQuartileA();
            totalStayed += r.stayedTopQuartile();
            totalFell += r.fellToBottomQuartile();
            totalVanished += r.vanishedAfterA();
            totalVanishedTop += r.vanishedAfterATopQuartile();
        }

        int measured = (int) results.stream().filter(CategoryResult::inHeadline).count();
        Double share = totalTopA == 0 ? null : (totalStayed * 100.0) / totalTopA;

        // The lower bound. Every top-quartile fund that stopped reporting is counted as having
        // failed to stay there - which is not a worst case so much as the likeliest one, because a
        // scheme that merges away is rarely the one that was doing well. The truth sits between
        // this and the figure above it, and the honest reading is this one.
        int topAIncludingDead = totalTopA + totalVanishedTop;
        Double lowerBound = topAIncludingDead == 0 ? null : (totalStayed * 100.0) / topAIncludingDead;

        Statistics.Correlation corr = Statistics.spearman(pooledRanks);

        Double perFund = totalTopA == 0 ? null : 100.0 / totalTopA;
        Double floor = perFund == null ? null
                : Math.max(WEAK_MARGIN_PP, RESOLUTION_MULTIPLE * perFund);
        PersistenceVerdict verdict = verdictFor(share, measured, floor);
        return new Result(results, measured, totalBoth, totalTopA, totalStayed, totalFell,
                share, lowerBound, perFund, floor, corr.present() ? corr.value() : null,
                totalVanished, totalVanishedTop, verdict,
                headline(verdict, share, lowerBound, totalStayed, totalTopA, perFund),
                caveats(totalVanishedTop, measured, perFund, floor));
    }

    private static CategoryResult measureCategory(SebiCategory category, List<Entry> entries,
                                                  List<double[]> pooledRanks) {
        List<Entry> all = entries == null ? List.of() : entries;
        String label = category == null ? "Not classified" : category.label();

        List<Entry> both = all.stream()
                .filter(e -> e.cagrA() != null && e.cagrB() != null)
                .toList();
        List<Entry> vanished = all.stream()
                .filter(e -> e.cagrA() != null && e.cagrB() == null)
                .toList();

        // Sectoral and thematic funds are ranked against each other by which sector ran, not by
        // which manager was good. Measured and shown, never folded into the headline.
        boolean rankable = category != null && category != SebiCategory.SECTORAL_THEMATIC;

        if (both.size() < MIN_FUNDS_PER_CATEGORY) {
            return new CategoryResult(category, label, both.size(), 0, 0, 0, null, null,
                    vanished.size(), 0, false,
                    both.size() + " funds have a complete series in both windows and "
                            + MIN_FUNDS_PER_CATEGORY + " are needed before a quarter of the category "
                            + "holds more than a rounding error - so no quartile is published, which "
                            + "says nothing about the funds in it");
        }

        List<Entry> byA = new ArrayList<>(both);
        byA.sort(Comparator.comparingDouble(Entry::cagrA).reversed());
        List<Entry> byB = new ArrayList<>(both);
        byB.sort(Comparator.comparingDouble(Entry::cagrB).reversed());

        Map<Long, Integer> quartileA = quartiles(byA);
        Map<Long, Integer> quartileB = quartiles(byB);

        int topA = 0;
        int stayed = 0;
        int fell = 0;
        for (Entry e : both) {
            if (quartileA.get(e.schemeCode()) == 1) {
                topA++;
                int qb = quartileB.get(e.schemeCode());
                if (qb == 1) stayed++;
                if (qb == 4) fell++;
            }
        }

        // Rank correlation over the category's own cross-section, then pooled. Ranking is the right
        // measure here for the same reason it is in the walk-forward harness: the question is
        // whether a better past place meant a better next place, not whether the relationship is
        // linear, and one fund that trebled must not carry the panel (Gotcha 92).
        List<double[]> pairs = new ArrayList<>();
        for (Entry e : both) {
            pairs.add(new double[]{e.cagrA(), e.cagrB()});
        }
        Statistics.Correlation corr = Statistics.spearman(pairs);

        int vanishedTop = 0;
        if (!vanished.isEmpty()) {
            // A fund that stopped reporting has no window-B place, so it cannot be in the matrix.
            // Where it stood in window A is knowable though, and that is the number that says how
            // much the matrix is flattered. Ranked against the survivors it is being compared with.
            List<Entry> aUniverse = new ArrayList<>(both);
            aUniverse.addAll(vanished);
            aUniverse.sort(Comparator.comparingDouble(Entry::cagrA).reversed());
            Map<Long, Integer> quartileAWithDead = quartiles(aUniverse);
            for (Entry e : vanished) {
                if (quartileAWithDead.get(e.schemeCode()) == 1) vanishedTop++;
            }
        }

        if (rankable) {
            pooledRanks.addAll(pairs);
        }

        // The percentage is withheld below the floor and the counts are kept: "1 of 7" cannot be
        // misread the way "14%" can, and on this archive every category is below the floor.
        Double share = (topA >= MIN_TOP_QUARTILE_FOR_SHARE) ? (stayed * 100.0) / topA : null;
        return new CategoryResult(category, label, both.size(), topA, stayed, fell,
                share,
                corr.present() ? corr.value() : null,
                vanished.size(), vanishedTop, rankable,
                rankable
                        ? stayed + " of the " + topA + " funds in the best quarter of this category "
                                + "in the first window were still there in the second"
                                + (share == null && topA > 0
                                        ? " - too few to turn into a percentage without one fund "
                                                + "moving it by " + points(100.0 / topA) : "")
                        : "ranked and shown, but kept out of the headline: these funds are not peers "
                                + "of one another, so a ranking of them mostly records which sector "
                                + "ran rather than which manager was good");
    }

    /**
     * Quartile per scheme, 1 = best, from a list already sorted best first.
     *
     * <p>Cut by position rather than by return, so an unusually tight or unusually wide spread of
     * returns cannot change how many funds land in each quarter. The question is about places in a
     * league table, and a league table's quarters are defined by position.
     */
    private static Map<Long, Integer> quartiles(List<Entry> sortedBest) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        int n = sortedBest.size();
        for (int i = 0; i < n; i++) {
            int q = (int) Math.floor((i * 4.0) / n) + 1;
            out.put(sortedBest.get(i).schemeCode(), Math.min(4, q));
        }
        return out;
    }

    /**
     * The band, with a floor that can never be finer than a few funds' worth of the sample.
     *
     * <p>Without the floor this returned {@code REVERSAL} on the first live run off a two-fund
     * difference. A directional claim about whether managers have skill is the single most
     * over-readable thing this class produces, so it is the one that must be hardest to trigger.
     */
    private static PersistenceVerdict verdictFor(Double sharePct, int categoriesMeasured,
                                                 Double bandFloorPp) {
        if (sharePct == null || categoriesMeasured == 0 || bandFloorPp == null) {
            return PersistenceVerdict.NOT_MEASURED;
        }
        double marginPp = sharePct - CHANCE * 100.0;
        if (marginPp >= Math.max(FOUND_MARGIN_PP, bandFloorPp)) return PersistenceVerdict.PERSISTENCE_FOUND;
        if (marginPp >= bandFloorPp) return PersistenceVerdict.WEAK_PERSISTENCE;
        if (marginPp <= -bandFloorPp) return PersistenceVerdict.REVERSAL;
        return PersistenceVerdict.NO_BETTER_THAN_CHANCE;
    }

    private static String headline(PersistenceVerdict verdict, Double sharePct, Double lowerPct,
                                   int stayed, int topA, Double perFundPp) {
        if (verdict == PersistenceVerdict.NOT_MEASURED) {
            return "There is not enough month-end history on file to ask this question yet. That is "
                    + "a gap in this app, not a finding about funds.";
        }
        String counts = stayed + " of the " + topA + " funds that were in the best quarter of their "
                + "category in the first window were still in the best quarter in the second - "
                + fmt(sharePct) + ", against the " + fmt(CHANCE * 100.0)
                + " you would get by drawing names out of a hat";
        String bound = lowerPct == null ? ""
                : ", or " + fmt(lowerPct) + " once the funds that were in the best quarter and then "
                        + "merged or wound up are counted as having failed";
        return switch (verdict) {
            case PERSISTENCE_FOUND -> counts + bound + ". On this one split, past ranking carried "
                    + "real information. That is worth taking seriously and it is still one period.";
            case WEAK_PERSISTENCE -> counts + bound + ". Slightly better than chance, and not by "
                    + "enough to build a buying decision on over one period.";
            case REVERSAL -> counts + bound + ". Worse than chance: the funds at the top of the "
                    + "first table were more likely than average to be at the bottom of the second.";
            default -> counts + bound + ". On this archive one fund is worth "
                    + (perFundPp == null ? "several points" : points(perFundPp))
                    + ", so that gap is a couple of funds: indistinguishable from chance. Knowing "
                    + "which funds had done best told you essentially nothing about which would do "
                    + "best next.";
        };
    }

    private static List<String> caveats(int vanishedTop, int categoriesMeasured,
                                        Double perFundPp, Double bandFloorPp) {
        List<String> out = new ArrayList<>();
        if (perFundPp != null) {
            out.add("One fund moving is worth " + points(perFundPp) + " here, so no direction is "
                    + "claimed unless the gap from chance exceeds " + points(bandFloorPp)
                    + " - a band finer than that would be reporting noise as a finding.");
        }
        out.add("This is ONE split of ONE market's history. It is evidence, not proof, and a "
                + "different pair of windows could read differently.");
        // Worded to avoid the tokens its own test bans. The first draft read "...which is why no
        // p-value or significance claim appears anywhere here" - true, and containing the exact
        // words that would make a reader skimming a truncated cell think one HAD been reported.
        // Gotcha 53 in the direction of prose, and its own test caught it (the NavFileAcceptance
        // lesson, second instance).
        out.add("The number of funds is not a sample size. Funds in a category hold heavily "
                + "overlapping stocks, so forty large-cap funds are nothing like forty independent "
                + "results - so no test of statistical reliability is reported here, because any "
                + "such test would assume an independence this data does not have.");
        if (vanishedTop > 0) {
            boolean one = vanishedTop == 1;
            out.add(vanishedTop + (one ? " fund was" : " funds were")
                    + " in the best quarter of " + (one ? "its" : "their")
                    + " category in the first window and then stopped "
                    + "reporting. " + (one ? "It cannot" : "They cannot")
                    + " appear in the second table at all, so the higher figure above is flattered "
                    + "by " + (one ? "its" : "their") + " absence.");
        }
        out.add("Sectoral and thematic funds are measured and shown but kept out of the headline: "
                + "ranking a pharmaceutical fund against a defence fund records which sector ran, "
                + "not which manager was good.");
        out.add("Quartiles are cut by position, so exactly a quarter of each category is in the "
                + "best quarter in each window by construction. The whole question is whether they "
                + "are the SAME funds.");
        if (categoriesMeasured > 0 && bandFloorPp != null) {
            // The EFFECTIVE bands, not the fixed constants. On this archive the resolution guard
            // widens the weak band from 30% to 32.5%, and a caveat naming rules that are not the
            // ones in force is worse than none (B-117, and Gotcha 126l's lesson about a caveat
            // describing a different system from the one running).
            double found = CHANCE * 100 + Math.max(FOUND_MARGIN_PP, bandFloorPp);
            double weak = CHANCE * 100 + bandFloorPp;
            out.add("The bands used to describe the result are judgement rather than measured "
                    + "thresholds, and the fixed part of them was set before the measurement ran. "
                    + "On this sample they work out at " + String.format("%.1f%%", found)
                    + " and above for a clear result, " + String.format("%.1f%%", weak)
                    + " and above for a weak one.");
        }
        return out;
    }

    /** A share of something: always a percentage. */
    private static String fmt(double pct) {
        return String.format("%.0f%%", pct);
    }

    /**
     * A gap between two shares: percentage POINTS, never a percentage.
     *
     * <p>Two different units, and this module exists partly to refuse exactly that confusion -
     * "20% against 25%" is a gap of five points, not of five percent, and writing the second is how
     * B-047's family of defect starts.
     */
    private static String points(double pp) {
        return String.format("%.1f points", pp);
    }
}
