package com.example.trading.analyst;

import com.example.trading.persistence.MultibaggerScoreEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Where this app's own screening and the brokerages' price targets agree, and where they do not
 * (SPEC §49.12). Pure: no repository, no clock, no I/O.
 *
 * <p><b>The headline measurement is a disagreement, and it is mostly mechanical.</b> Across the
 * live cross-section the correlation between a house's claimed upside and this app's composite is
 * about <b>-0.33</b>: the more upside a brokerage claims, the <em>lower</em> the composite scores
 * that stock. That is not the sell-side being wrong. Roughly 54% of the composite's weight is
 * price behaviour (momentum, relative strength, price structure), which rewards a stock near its
 * highs, while a large claimed upside is by construction a stock trading far below where the
 * house thinks it belongs. <b>The two are measuring near-opposite things</b>, which is exactly
 * why the composite cannot be used to "verify" a target and why the fundamental checks are the
 * ones that can.
 *
 * <p>Three refusals hold this together:
 *
 * <ul>
 *   <li><b>No verdict on a target.</b> Nothing here says a target is right, achievable or worth
 *       acting on — that would be short-term price prediction (§19) and a sixth surface answering
 *       "is this a good buy" (Gotcha 85). It reports where two independent measurements point
 *       different ways and leaves the reading to the investor.</li>
 *   <li><b>"No target on file" is not "uncovered".</b> It means no house published one into the
 *       feed this app reads. A high-scoring stock with no target may be genuinely neglected —
 *       which §12.10's under-the-radar lens treats as interesting rather than as a gap — or may
 *       simply be covered by desks this feed does not carry.</li>
 *   <li><b>A stock trading above every open target is a fact, not a sell signal.</b> It says the
 *       price has passed what the quoted houses last published, which is the sharpest form the
 *       disagreement takes and the clearest case for looking harder before adding.</li>
 * </ul>
 */
public final class AnalystOverlap {

    private AnalystOverlap() {
    }

    /** One score band and how much of it the brokerages cover. */
    public record Band(String label, int minScore, int stocks, int withOpenTarget) {
        public Double coveragePercent() {
            return stocks == 0 ? null : 100.0 * withOpenTarget / stocks;
        }
    }

    /** One screened stock joined to whatever open targets sit on it. */
    public record Row(String symbol,
                      int composite,
                      String verdict,
                      String qualityVerdict,
                      String forensicFlags,
                      double price,
                      int targets,
                      int houses,
                      Double highestTarget,
                      Double lowestTarget,
                      Double medianTarget,
                      Double upsideToMedianPct,
                      Double upsideToHighestPct) {

        /**
         * The price has passed every target the quoted houses published.
         *
         * <p>Stated as a comparison, never as a conclusion: it is the same fact whether the
         * houses were too cautious or the market has run ahead, and this app has no way to tell
         * those apart.
         */
        public boolean abovePublishedTargets() {
            return highestTarget != null && price > 0 && highestTarget < price;
        }
    }

    /**
     * One thing the reader can actually do, and which list it applies to.
     *
     * <p>Separate from {@link Result#notes()} on purpose. The notes explain why the numbers look
     * the way they do; these say what to do next. Shipping only the former is what this screen did
     * first, and the investor's reaction to it was the correct one: a page of measurements with no
     * next step is a page that gets read once.
     */
    public record Action(String title, String guidance) {
    }

    /** The whole read model. */
    public record Result(LocalDate screeningDate,
                         int screenedStocks,
                         int screenedWithOpenTarget,
                         int goodStocks,
                         int goodWithOpenTarget,
                         List<Band> bands,
                         Double upsideVsCompositeCorrelation,
                         Integer correlationSample,
                         List<Row> good,
                         List<Row> abovePublishedTargets,
                         List<Row> goodWithoutTarget,
                         List<Row> strongestAgreement,
                         List<Action> actions,
                         Map<String, String> notes) {
    }

    /**
     * The app's own word for a stock worth calling a candidate.
     *
     * <p>Deliberately the stored <em>verdict</em> rather than a score threshold. The obvious
     * alternative, {@code minScoreForCandidate}, is only half of {@code isCandidate()} — the
     * percentile gate is the other half — so on its own it admits stocks the screener itself does
     * not call candidates, and this screen would then disagree with the screener about which
     * stocks are good. One question, one definition (Gotcha 85).
     */
    static final Set<String> GOOD_VERDICTS = Set.of("STRONG_MULTIBAGGER", "POTENTIAL_MULTIBAGGER");

    static boolean isGood(Row r) {
        return r.verdict() != null && GOOD_VERDICTS.contains(r.verdict());
    }

    /**
     * How many independent houses have to be quoting before the agreement is worth a shortlist.
     *
     * <p>Set against the measured cross-section rather than assumed (the §12.11 discipline): of the
     * 88 good stocks carrying a live target, <b>39 are quoted by exactly one house</b> and the
     * median is two. So a headline "44% upside" is, more often than not, one analyst's opinion
     * published once — while three or more desks arriving at a target above the price is a
     * different quality of evidence entirely, and narrows 88 rows to about 20.
     *
     * <p>This is a count of <em>firms</em>, never of notes: one house staggering three revisions
     * across a quarter is one opinion, and {@link Row#houses()} is deduplicated for that reason —
     * the same rule B-041 applied to insider filings.
     */
    static final int MIN_HOUSES_FOR_AGREEMENT = 3;

    /**
     * Join one screening run to the open book.
     *
     * @param screened    every row from the latest screening run that has rows
     * @param openByStock open (PENDING) targets, grouped by the screening symbol
     */
    public static Result compute(List<MultibaggerScoreEntity> screened,
                                 Map<String, List<AnalystTargetEntity>> openByStock) {
        List<Row> rows = new ArrayList<>();
        for (MultibaggerScoreEntity s : screened) {
            rows.add(row(s, openByStock.getOrDefault(s.getSymbol(), List.of())));
        }

        LocalDate date = screened.isEmpty() ? null : screened.get(0).getScreeningDate();
        int withTarget = (int) rows.stream().filter(r -> r.targets() > 0).count();

        List<Row> good = rows.stream()
                .filter(AnalystOverlap::isGood)
                .sorted(Comparator.comparingInt(Row::composite).reversed())
                .toList();
        List<Row> goodWithTarget = good.stream().filter(r -> r.targets() > 0).toList();
        List<Row> goodWithout = good.stream().filter(r -> r.targets() == 0).toList();

        List<Row> above = rows.stream()
                .filter(AnalystOverlap::isGood)
                .filter(Row::abovePublishedTargets)
                .sorted(Comparator.comparing(r -> r.upsideToHighestPct() == null ? 0 : r.upsideToHighestPct()))
                .toList();

        // Correlation over every screened stock that carries a target, not just the good ones -
        // restricting it to high scorers would measure the relationship on the half of the range
        // that was selected for, which is how a mechanical artefact becomes a finding.
        List<Double> upside = new ArrayList<>();
        List<Double> composite = new ArrayList<>();
        for (Row r : rows) {
            if (r.targets() == 0 || r.upsideToMedianPct() == null) continue;
            upside.add(r.upsideToMedianPct());
            composite.add((double) r.composite());
        }
        Double corr = pearson(upside, composite);

        // The shortest list on the screen and the one carrying the most evidence: this app rates
        // the business highly AND several independent desks have a live target on it.
        List<Row> agreed = goodWithTarget.stream()
                .filter(r -> r.houses() >= MIN_HOUSES_FOR_AGREEMENT)
                .sorted(Comparator.comparingInt(Row::houses).reversed()
                        .thenComparing(Comparator.comparingInt(Row::composite).reversed()))
                .toList();

        return new Result(date, screened.size(), withTarget, good.size(), goodWithTarget.size(),
                bands(rows), corr, upside.size(),
                goodWithTarget, above, goodWithout, agreed,
                actions(above.size(), agreed.size(), goodWithout.size(), goodWithTarget),
                notes(corr));
    }

    static Row row(MultibaggerScoreEntity s, List<AnalystTargetEntity> open) {
        Set<String> houses = new TreeSet<>();
        List<Double> targets = new ArrayList<>();
        for (AnalystTargetEntity t : open) {
            if (t.getBrokerage() != null) houses.add(t.getBrokerage());
            if (t.getTargetPrice() != null && t.getTargetPrice() > 0) targets.add(t.getTargetPrice());
        }
        double price = s.getCurrentPrice();
        Double median = AnalystTrackRecord.median(targets);
        Double high = targets.stream().max(Double::compareTo).orElse(null);
        Double low = targets.stream().min(Double::compareTo).orElse(null);

        return new Row(s.getSymbol(), s.getCompositeScore(), s.getVerdict(),
                s.getFinancialQualityVerdict(), s.getForensicFlags(), price,
                open.size(), houses.size(), high, low, median,
                upsidePct(median, price), upsidePct(high, price));
    }

    /** Percent from the stock's price to a target, or null when either leg is missing. */
    static Double upsidePct(Double target, double price) {
        if (target == null || price <= 0) return null;
        return (target - price) / price * 100.0;
    }

    static List<Band> bands(List<Row> rows) {
        List<Band> out = new ArrayList<>();
        out.add(band(rows, "80 and above", 80, Integer.MAX_VALUE));
        out.add(band(rows, "70 to 79", 70, 80));
        out.add(band(rows, "65 to 69", 65, 70));
        out.add(band(rows, "50 to 64", 50, 65));
        out.add(band(rows, "below 50", Integer.MIN_VALUE, 50));
        return out;
    }

    private static Band band(List<Row> rows, String label, int from, int toExclusive) {
        List<Row> in = rows.stream()
                .filter(r -> r.composite() >= from && r.composite() < toExclusive)
                .toList();
        return new Band(label, from, in.size(), (int) in.stream().filter(r -> r.targets() > 0).count());
    }

    /**
     * What a reader can actually do with the three lists below.
     *
     * <p>Every count here is passed in from the list it describes rather than recomputed, so the
     * sentence and the table underneath it cannot drift apart (B-098).
     *
     * <p>The vocabulary is bounded the same way every other verdict in this app is (§20 rule 10):
     * these point at a list to read, never at a transaction. "Look harder before adding" is the
     * accumulation-timing question §12.11 already answers; "sell" is not a word this screen owns,
     * because nothing here measures whether you should still own a business.
     */
    static List<Action> actions(int above, int agreed, int uncovered, List<Row> goodWithTarget) {
        List<Action> out = new ArrayList<>();

        if (agreed > 0) {
            out.add(new Action(
                    "Start with the " + agreed + " both sides rate",
                    "This app scores the business highly and at least " + MIN_HOUSES_FOR_AGREEMENT
                            + " separate firms are quoting a live target on it. Two unrelated "
                            + "methods landing on the same company is the strongest thing on this "
                            + "page, and it is also the shortest list, so read these first. Note "
                            + "the upside column as you go: where several firms are quoting and "
                            + "that figure is negative, they cover the company and still think the "
                            + "price has got ahead of it — which is the disagreement worth a "
                            + "closer look rather than a row to skip."));
        }

        if (above > 0) {
            out.add(new Action(
                    above + " have already passed every target on them",
                    "The share price is now above the highest figure the quoted firms published and "
                            + "have not revised. If you hold one, look harder before adding at "
                            + "today's price: everyone publishing on it currently thinks it is "
                            + "worth less than you would pay. It says nothing about whether the "
                            + "business is still a good one — check the quality column beside it."));
        }

        if (uncovered > 0) {
            out.add(new Action(
                    uncovered + " we rate that nobody is quoting",
                    "Treat this as a reading queue rather than a warning. No target simply means no "
                            + "firm published one into the feed this app reads, and a good business "
                            + "nobody is talking about is the thing the under-the-radar lens exists "
                            + "to find."));
        }

        long single = goodWithTarget.stream().filter(r -> r.houses() == 1).count();
        if (!goodWithTarget.isEmpty()) {
            out.add(new Action(
                    "Read the firm count before the claimed upside",
                    single + " of these " + goodWithTarget.size() + " stocks are quoted by exactly "
                            + "one firm. A headline upside from a single desk is one person's "
                            + "opinion published once; the same figure from four desks is evidence. "
                            + "Sort by the Firms column before sorting by upside."));
        }

        out.add(new Action(
                "The correlation figure is not a task",
                "It is a check on the two systems, not advice. A negative reading is expected here, "
                        + "for the reason set out in the notes on this page — it does not mean "
                        + "either side is wrong, and nothing follows from it."));

        return out;
    }

    /**
     * The lines that must travel with these numbers.
     *
     * <p>A negative correlation between "how much upside an analyst claims" and "how well this app
     * scores the stock" reads, to someone who did not build either, as one of them being wrong.
     * It is mostly an artefact of what each measures, and saying so is the difference between a
     * finding and a misleading number.
     */
    static Map<String, String> notes(Double corr) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("whatThisIs", "Two independent readings of the same stocks: this app's own screening "
                + "score, and the price targets brokerages have published and not yet revised. "
                + "Nothing here is a view on whether a target will be reached.");
        m.put("whyTheyDisagree", "Expect them to point different ways, and mostly for a mechanical "
                + "reason rather than because either is wrong. About half this app's composite is "
                + "price behaviour, which rewards a stock trading near its highs — while a large "
                + "claimed upside is, by definition, a stock trading well below where the house "
                + "thinks it belongs. The two are close to measuring opposite things.");
        m.put("soWhatVerifies", "Because of that, the composite cannot be used to check a target. "
                + "The measurements that can are the fundamental ones — what growth the target "
                + "would require against what the business has actually delivered, whether the "
                + "accounts carry a flag, and whether it has compounded before.");
        m.put("noTargetIsNotUncovered", "“No target on file” means no house published one into "
                + "the feed this app reads. It is not evidence that nobody follows the stock, and "
                + "a good business nobody quotes is interesting rather than suspect.");
        m.put("aboveTarget", "A stock trading above every open target on it has passed what the "
                + "quoted houses last published. That is the sharpest form this disagreement "
                + "takes. It is a reason to look harder before adding, not an instruction to sell.");
        if (corr != null) {
            m.put("measured", String.format(
                    "Measured on this screening run: correlation between claimed upside and this "
                            + "app's composite is %.2f.", corr));
        }
        return m;
    }

    /** Pearson, or null when there is nothing to correlate. */
    static Double pearson(List<Double> a, List<Double> b) {
        int n = Math.min(a.size(), b.size());
        if (n < 3) return null;
        double ma = a.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double mb = b.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            double x = a.get(i) - ma, y = b.get(i) - mb;
            num += x * y;
            da += x * x;
            db += y * y;
        }
        double den = Math.sqrt(da * db);
        return den == 0 ? null : num / den;
    }
}
