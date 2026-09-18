package com.example.trading.earnings;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Was this a good quarter?" — the rule table (SPEC.md §50.3).
 *
 * <h2>What this answers, and what it refuses to</h2>
 * Four questions are asked of a filed quarter: did revenue grow year-on-year, did profit, did the
 * net margin hold, and did the quarter land where the company's own recent trend pointed. The
 * output is one of {@code STRONG / IN_LINE / WEAK / CONCERNING / NOT_MEASURED} and <b>nothing
 * else</b> — no price, no target, no instruction to transact (SPEC §19, §20 rule 10). A weak
 * quarter is not a sell and a strong one is not a buy: this exists so that the next time a
 * holding falls 20% the investor can tell a business that is deteriorating from a price that is.
 *
 * <h2>Three refusals that carry the feature</h2>
 * <ol>
 *   <li><b>Year-on-year, not quarter-on-quarter, decides.</b> Indian businesses are seasonal —
 *       festive quarters, monsoons, a March year-end push — so a QoQ fall is usually the calendar
 *       rather than the company. QoQ is computed and shown, and it is not a signal.</li>
 *   <li><b>A comparison across reporting bases is refused, not converted.</b> Standalone revenue
 *       can be half the group figure, so a consolidated quarter measured against a standalone one
 *       manufactures a collapse that every downstream reader takes as real (Gotcha 73). Where the
 *       two quarters disagree on basis the leg reads NOT_MEASURED and says why.</li>
 *   <li><b>Fewer than two measured signals is NOT_MEASURED.</b> Not IN_LINE. "We could not tell"
 *       and "it was unremarkable" are different facts and the second is the more reassuring, which
 *       is exactly why they must not render alike (Gotcha 44, 121).</li>
 * </ol>
 *
 * <h2>Counting, not averaging</h2>
 * The verdict counts how many of the four signals came back strong and how many weak. It does not
 * average them: an average lets one enormous revenue jump carry a quarter in which profit halved
 * and the margin collapsed, which is the failure §43 documents for return on capital (Gotcha 103).
 *
 * <p>Pure: no clock, no I/O, no Spring, no repository.
 */
public final class QuarterlyResultRead {

    private QuarterlyResultRead() {
    }

    // ---- Thresholds. Growth bars are deliberately modest: this asks "did the business move
    // forward", not "is this a multibagger" — the composite already asks the second question.
    static final double STRONG_REVENUE_GROWTH = 10.0;
    static final double STRONG_PROFIT_GROWTH = 15.0;
    /** Margin moves inside this band are noise, not a trend. */
    static final double MARGIN_NOISE_PP = 0.5;
    /** A narrowing loss has to narrow by more than this to count as anything at all. */
    static final double LOSS_NARROWING_PERCENT = 10.0;

    /** Signals measured. Four is the denominator every coverage statement here uses. */
    public static final int TOTAL_SIGNALS = 4;
    /** Below this many measured signals there is no verdict (refusal 3 above). */
    public static final int MIN_MEASURED_SIGNALS = 2;
    /** A STRONG verdict needs this many strong signals and no weak one. */
    static final int STRONG_SIGNALS_REQUIRED = 3;

    public enum Verdict {
        /** Grew on every measure that could be checked. */
        STRONG,
        /** Nothing much moved either way — the ordinary answer. */
        IN_LINE,
        /** Two of the four measures went backwards. */
        WEAK,
        /** Three went backwards, or the company lost money where it used to make it. */
        CONCERNING,
        /** Not enough comparable history on file to say. Never a finding about the business. */
        NOT_MEASURED
    }

    public enum Status { STRONG, OK, WEAK, NOT_MEASURED }

    /**
     * One of the four checks.
     *
     * @param figure the number the status was reached on, so a reader can disagree with it
     * @param text   plain English, per SPEC §21 — the investor is not an analyst
     */
    public record Signal(String key, String label, Status status, Double figure, String text) {
    }

    /**
     * @param verdict         the headline
     * @param headline        one sentence a beginner can act on understanding
     * @param signals         all four, including the ones that could not be measured
     * @param measuredSignals how many of {@link #TOTAL_SIGNALS} produced a status
     * @param basisNote       null unless the comparison basis is unconfirmed or mixed
     * @param comparedWith    the fiscal label of the year-ago quarter, when one was used
     */
    public record Result(String symbol,
                         LocalDate quarterEnd,
                         String fiscalLabel,
                         LocalDate availableFrom,
                         Boolean availableFromEstimated,
                         Boolean consolidated,
                         Boolean revised,
                         Verdict verdict,
                         String headline,
                         List<Signal> signals,
                         int measuredSignals,
                         int totalSignals,
                         Double revenueYoyPercent,
                         Double profitYoyPercent,
                         Double marginDeltaPp,
                         Double revenueQoqPercent,
                         Double profitQoqPercent,
                         Double revenue,
                         Double profit,
                         Double netMargin,
                         Double eps,
                         String trendBreak,
                         Double trendSurprisePercent,
                         String comparedWith,
                         String basisNote) {

        /** True when a verdict was actually reached. */
        public boolean measured() {
            return verdict != Verdict.NOT_MEASURED;
        }

        /** The two verdicts that deserve the investor's attention today. */
        public boolean needsAttention() {
            return verdict == Verdict.WEAK || verdict == Verdict.CONCERNING;
        }
    }

    /** A read carrying nothing, for a company with no filed quarter at all. */
    public static Result notMeasured(String symbol, String why) {
        return new Result(symbol, null, null, null, null, null, null,
                Verdict.NOT_MEASURED, why, List.of(), 0, TOTAL_SIGNALS,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Read the newest quarter on file against its history.
     *
     * @param quarters every filed quarter for one company, in any order. Nothing is fetched.
     */
    public static Result of(String symbol, List<QuarterlyResultEntity> quarters) {
        if (quarters == null || quarters.isEmpty()) {
            return notMeasured(symbol, "No quarterly result has been captured for this company yet.");
        }
        List<QuarterlyResultEntity> ordered = new ArrayList<>(quarters);
        ordered.removeIf(q -> q == null || q.getQuarterEnd() == null);
        if (ordered.isEmpty()) {
            return notMeasured(symbol, "No quarterly result has been captured for this company yet.");
        }
        ordered.sort(Comparator.comparing(QuarterlyResultEntity::getQuarterEnd).reversed());

        QuarterlyResultEntity latest = ordered.get(0);
        QuarterlyResultEntity prior = find(ordered, latest, false);
        QuarterlyResultEntity yearAgo = find(ordered, latest, true);

        List<Signal> signals = new ArrayList<>();
        String basisNote = null;

        // ---- Year-on-year legs. Same season, so seasonality cancels.
        Basis yoyBasis = basis(latest, yearAgo);
        Double revenueYoy = yoyBasis == Basis.MISMATCH ? null
                : growth(latest.getRevenue(), yearAgo == null ? null : yearAgo.getRevenue());
        Double profitYoy = yoyBasis == Basis.MISMATCH ? null
                : growth(latest.getProfit(), yearAgo == null ? null : yearAgo.getProfit());
        Double marginDelta = yoyBasis == Basis.MISMATCH ? null
                : delta(latest.getNetMargin(), yearAgo == null ? null : yearAgo.getNetMargin());

        if (yoyBasis == Basis.MISMATCH) {
            basisNote = "This quarter was filed " + basisWord(latest.getConsolidated())
                    + " and the year-ago quarter " + basisWord(yearAgo.getConsolidated())
                    + ". Those are different scopes of the same company, so the app does not compare "
                    + "them — a growth rate taken across the two would be arithmetic on two "
                    + "different businesses.";
        } else if (yoyBasis == Basis.UNKNOWN && yearAgo != null) {
            basisNote = "The filing did not state whether these figures are consolidated or "
                    + "standalone, so the comparison assumes the two quarters are on the same basis.";
        }

        signals.add(revenueSignal(revenueYoy, yoyBasis, yearAgo));
        signals.add(profitSignal(profitYoy, latest, yearAgo, yoyBasis));
        signals.add(marginSignal(marginDelta, latest));
        signals.add(trendSignal(ordered, latest));

        // ---- Quarter-on-quarter, computed and shown, never a signal (refusal 1).
        Basis qoqBasis = basis(latest, prior);
        Double revenueQoq = qoqBasis == Basis.MISMATCH ? null
                : growth(latest.getRevenue(), prior == null ? null : prior.getRevenue());
        Double profitQoq = qoqBasis == Basis.MISMATCH ? null
                : growth(latest.getProfit(), prior == null ? null : prior.getProfit());

        int measured = (int) signals.stream().filter(s -> s.status() != Status.NOT_MEASURED).count();
        int strong = (int) signals.stream().filter(s -> s.status() == Status.STRONG).count();
        int weak = (int) signals.stream().filter(s -> s.status() == Status.WEAK).count();

        Verdict verdict = verdict(latest, yearAgo, yoyBasis, measured, strong, weak);
        Signal trend = signals.get(3);

        return new Result(symbol,
                latest.getQuarterEnd(),
                label(latest),
                latest.getAvailableFrom(),
                latest.getAvailableFromEstimated(),
                latest.getConsolidated(),
                latest.getRevised(),
                verdict,
                headline(verdict, latest),
                List.copyOf(signals),
                measured,
                TOTAL_SIGNALS,
                revenueYoy == null ? null : round(revenueYoy),
                profitYoy == null ? null : round(profitYoy),
                marginDelta == null ? null : round(marginDelta),
                revenueQoq == null ? null : round(revenueQoq),
                profitQoq == null ? null : round(profitQoq),
                latest.getRevenue(), latest.getProfit(), latest.getNetMargin(), latest.getEps(),
                trendVerdictOf(trend), trend.figure(),
                yearAgo == null ? null : label(yearAgo),
                basisNote);
    }

    // ------------------------------------------------------------------ the verdict

    private static Verdict verdict(QuarterlyResultEntity latest, QuarterlyResultEntity yearAgo,
                                   Basis basis, int measured, int strong, int weak) {
        // A loss outranks the count. A company that used to make money and now does not is the
        // single most important thing a quarterly read can surface, and it can be true while
        // revenue grows — which is precisely the case a growth-weighted count would miss.
        Double profit = latest.getProfit();
        if (profit != null && profit < 0) {
            Double was = (yearAgo != null && basis != Basis.MISMATCH) ? yearAgo.getProfit() : null;
            if (was == null) return Verdict.WEAK;                 // loss, nothing to compare it to
            if (was >= 0) return Verdict.CONCERNING;              // swung from profit to loss
            double narrowing = (Math.abs(was) - Math.abs(profit)) / Math.abs(was) * 100.0;
            return narrowing > LOSS_NARROWING_PERCENT ? Verdict.WEAK : Verdict.CONCERNING;
        }

        if (measured < MIN_MEASURED_SIGNALS) return Verdict.NOT_MEASURED;
        if (weak >= 3) return Verdict.CONCERNING;
        if (weak >= 2) return Verdict.WEAK;
        if (strong >= STRONG_SIGNALS_REQUIRED && weak == 0) return Verdict.STRONG;
        return Verdict.IN_LINE;
    }

    // ------------------------------------------------------------------ the four signals

    private static Signal revenueSignal(Double yoy, Basis basis, QuarterlyResultEntity yearAgo) {
        if (basis == Basis.MISMATCH) {
            return new Signal("REVENUE_YOY", "Sales vs a year ago", Status.NOT_MEASURED, null,
                    "Not compared — the two quarters were filed on different reporting bases.");
        }
        if (yoy == null) {
            return new Signal("REVENUE_YOY", "Sales vs a year ago", Status.NOT_MEASURED, null,
                    yearAgo == null
                            ? "No filing on record for the same quarter last year."
                            : "Sales were not tagged in one of the two filings.");
        }
        Status st = yoy >= STRONG_REVENUE_GROWTH ? Status.STRONG : yoy >= 0 ? Status.OK : Status.WEAK;
        return new Signal("REVENUE_YOY", "Sales vs a year ago", st, round(yoy),
                String.format("Sales %s %.1f%% against the same quarter last year.",
                        yoy >= 0 ? "grew" : "fell", Math.abs(yoy)));
    }

    private static Signal profitSignal(Double yoy, QuarterlyResultEntity latest,
                                       QuarterlyResultEntity yearAgo, Basis basis) {
        if (basis == Basis.MISMATCH) {
            return new Signal("PROFIT_YOY", "Profit vs a year ago", Status.NOT_MEASURED, null,
                    "Not compared — the two quarters were filed on different reporting bases.");
        }
        Double was = yearAgo == null ? null : yearAgo.getProfit();
        Double now = latest.getProfit();
        if (now != null && now < 0) {
            return new Signal("PROFIT_YOY", "Profit vs a year ago", Status.WEAK, now,
                    was != null && was >= 0
                            ? String.format("The company lost Rs %.0f cr this quarter, having made "
                                    + "Rs %.0f cr in the same quarter last year.", Math.abs(now), was)
                            : String.format("The company lost Rs %.0f cr this quarter.", Math.abs(now)));
        }
        // A percentage growth out of a loss is undefined in the direction that matters: recovering
        // from -100 to +10 is not "110% growth", it is a different business outcome (the B-113
        // lesson, one scale down).
        if (was != null && was < 0 && now != null && now >= 0) {
            return new Signal("PROFIT_YOY", "Profit vs a year ago", Status.STRONG, null,
                    String.format("Back to a profit of Rs %.0f cr, from a loss a year ago. No growth "
                            + "rate is quoted — a recovery from a loss has none.", now));
        }
        if (yoy == null) {
            return new Signal("PROFIT_YOY", "Profit vs a year ago", Status.NOT_MEASURED, null,
                    yearAgo == null
                            ? "No filing on record for the same quarter last year."
                            : "Profit was not tagged in one of the two filings.");
        }
        Status st = yoy >= STRONG_PROFIT_GROWTH ? Status.STRONG : yoy >= 0 ? Status.OK : Status.WEAK;
        return new Signal("PROFIT_YOY", "Profit vs a year ago", st, round(yoy),
                String.format("Profit %s %.1f%% against the same quarter last year.",
                        yoy >= 0 ? "grew" : "fell", Math.abs(yoy)));
    }

    private static Signal marginSignal(Double deltaPp, QuarterlyResultEntity latest) {
        if (deltaPp == null) {
            return new Signal("MARGIN_YOY", "Profit margin", Status.NOT_MEASURED, null,
                    "The margin could not be compared with the same quarter last year.");
        }
        Status st = deltaPp >= MARGIN_NOISE_PP ? Status.STRONG
                : deltaPp > -MARGIN_NOISE_PP ? Status.OK : Status.WEAK;
        String now = latest.getNetMargin() == null ? ""
                : String.format(" It now keeps %.1f%% of sales as profit.", latest.getNetMargin());
        return new Signal("MARGIN_YOY", "Profit margin", st, round(deltaPp),
                String.format("The margin %s %.1f percentage points against the same quarter last year.",
                        deltaPp >= 0 ? "widened by" : "narrowed by", Math.abs(deltaPp)) + now);
    }

    /**
     * The quarter against the company's own three-quarter trend — the honest stand-in for an
     * analyst estimate this app does not have (§24, {@link TrendBreak}).
     */
    private static Signal trendSignal(List<QuarterlyResultEntity> newestFirst,
                                      QuarterlyResultEntity latest) {
        String label = "Against its own recent trend";
        if (newestFirst.size() < TrendBreak.MIN_QUARTERS) {
            return new Signal("VS_OWN_TREND", label, Status.NOT_MEASURED, null,
                    "Needs four consecutive quarters on file; " + newestFirst.size()
                            + (newestFirst.size() == 1 ? " is" : " are") + " captured so far.");
        }
        // The three quarters immediately before this one, oldest first, and only if they really
        // are consecutive — a gap in the capture would otherwise be fitted as if it were a trend.
        List<Double> series = new ArrayList<>();
        LocalDate expect = latest.getQuarterEnd();
        for (int i = 1; i <= 3; i++) {
            QuarterlyResultEntity q = newestFirst.get(i);
            if (!FiscalQuarter.isQuarterApart(expect, q.getQuarterEnd())) {
                return new Signal("VS_OWN_TREND", label, Status.NOT_MEASURED, null,
                        "The three quarters before this one are not consecutive on file, so no "
                                + "trend can be fitted through them.");
            }
            if (basis(latest, q) == Basis.MISMATCH) {
                return new Signal("VS_OWN_TREND", label, Status.NOT_MEASURED, null,
                        "The recent quarters are not all on the same reporting basis.");
            }
            series.add(0, q.getProfit());
            expect = q.getQuarterEnd();
        }
        Double projected = TrendBreak.project(series);
        Double surprise = TrendBreak.surprise(latest.getProfit(), projected);
        if (surprise == null) {
            return new Signal("VS_OWN_TREND", label, Status.NOT_MEASURED, null,
                    "Profit was not tagged in enough of the recent filings to fit a trend.");
        }
        String verdict = TrendBreak.classify(surprise);
        Status st = TrendBreak.isPositive(verdict) ? Status.STRONG
                : TrendBreak.isNegative(verdict) ? Status.WEAK : Status.OK;
        return new Signal("VS_OWN_TREND", label, st, round(surprise),
                String.format("Profit came in %.0f%% %s where its own last three quarters pointed.",
                        Math.abs(surprise), surprise >= 0 ? "above" : "below"));
    }

    private static String trendVerdictOf(Signal trend) {
        return trend.status() == Status.NOT_MEASURED ? TrendBreak.INSUFFICIENT
                : TrendBreak.classify(trend.figure());
    }

    // ------------------------------------------------------------------ headline

    private static String headline(Verdict v, QuarterlyResultEntity latest) {
        String q = label(latest);
        return switch (v) {
            case STRONG -> q + " grew on every measure the app could check.";
            case IN_LINE -> q + " was broadly in line with the same quarter last year.";
            case WEAK -> q + " went backwards on two of the four measures checked.";
            case CONCERNING -> latest.getProfit() != null && latest.getProfit() < 0
                    ? q + " was loss-making."
                    : q + " went backwards on most of what the app could check.";
            case NOT_MEASURED -> "Not enough comparable history on file to judge " + q
                    + " — that is a gap in what has been captured, not a finding about the company.";
        };
    }

    // ------------------------------------------------------------------ helpers

    private enum Basis { MATCH, MISMATCH, UNKNOWN }

    /**
     * Whether two quarters can be compared at all.
     *
     * <p>A null on either side is UNKNOWN rather than MISMATCH: NSE genuinely leaves the field
     * blank on some filings, and refusing every such comparison would cost more coverage than the
     * risk justifies — so it is allowed and the caller states the assumption (Gotcha 121's rule:
     * an assumption may be acted on, but never presented as a fact).
     */
    private static Basis basis(QuarterlyResultEntity a, QuarterlyResultEntity b) {
        if (a == null || b == null) return Basis.UNKNOWN;
        Boolean x = a.getConsolidated();
        Boolean y = b.getConsolidated();
        if (x == null || y == null) return Basis.UNKNOWN;
        return x.equals(y) ? Basis.MATCH : Basis.MISMATCH;
    }

    private static String basisWord(Boolean consolidated) {
        return Boolean.TRUE.equals(consolidated) ? "consolidated"
                : Boolean.FALSE.equals(consolidated) ? "standalone" : "on an unstated basis";
    }

    private static String label(QuarterlyResultEntity q) {
        return q.getFiscalLabel() != null ? q.getFiscalLabel() : FiscalQuarter.label(q.getQuarterEnd());
    }

    /** The year-ago quarter, or the immediately preceding one. Null when not on file. */
    private static QuarterlyResultEntity find(List<QuarterlyResultEntity> newestFirst,
                                              QuarterlyResultEntity latest, boolean yearAgo) {
        for (int i = 1; i < newestFirst.size(); i++) {
            QuarterlyResultEntity q = newestFirst.get(i);
            boolean hit = yearAgo
                    ? FiscalQuarter.isYearApart(latest.getQuarterEnd(), q.getQuarterEnd())
                    : FiscalQuarter.isQuarterApart(latest.getQuarterEnd(), q.getQuarterEnd());
            if (hit) return q;
        }
        return null;
    }

    /**
     * Percentage growth. Null unless both legs exist and the base is a meaningful positive
     * number — growth off a zero or negative base is not a percentage (B-113's rule).
     */
    private static Double growth(Double now, Double was) {
        if (now == null || was == null || was <= 0) return null;
        return (now - was) / was * 100.0;
    }

    private static Double delta(Double now, Double was) {
        if (now == null || was == null) return null;
        return now - was;
    }

    private static Double round(Double d) {
        return d == null ? null : Math.round(d * 10.0) / 10.0;
    }
}
