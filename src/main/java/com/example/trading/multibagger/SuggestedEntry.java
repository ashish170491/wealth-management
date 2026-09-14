package com.example.trading.multibagger;

import com.example.trading.watchlist.BuyTimingVerdict.Verdict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Where do I buy this?" for a long-horizon position (SPEC §12.12).
 *
 * <p><strong>The answer is a ladder, not a price.</strong> Over a holding period measured in years
 * the entry tick is a second-order concern: a business that triples in three years rewards a 5%
 * better entry with about 1.7% of extra return, while "waiting for a dip" that never arrives costs
 * the whole position. The reasons to care about entry at all are behavioural — buying a vertical
 * extension makes an investor likely to sell the first ordinary 20% retrace — and the volatility
 * drag a fixed budget suffers when it is all spent at the top of a range. Neither is answered by a
 * single number, and a single number invites the two failures this class exists to prevent:
 * treating a level as a forecast, and contradicting the verdict printed beside it (B-062, B-068).
 *
 * <p>So the output is <strong>three equal tranches</strong> — the shape SPEC §8's accumulation
 * planner already endorses ("price-ladder mode: N tranches at descending price levels"). Equal
 * thirds deliberately: unequal weights would be free parameters tuned on nothing, and this codebase
 * has learned what that costs (Gotcha 30).
 *
 * <h2>What the verdict changes</h2>
 * The verdict sets where the ladder <em>starts</em>, which is what keeps the plan and the words
 * beside it consistent by construction rather than by a guard:
 * <ul>
 *   <li>{@code BUY_NOW} / {@code ACCUMULATE} — the first tranche is at today's price. Not owning a
 *       business the app rates highly is the larger risk.</li>
 *   <li>{@code WAIT_FOR_PULLBACK} / {@code HOLD_OFF} — every tranche sits <em>below</em> today's
 *       price, so a waiting verdict can never be printed next to a buy-now level.</li>
 *   <li>{@code AVOID} — no ladder at all. A price here reads as "buy it cheaper".</li>
 * </ul>
 *
 * <h2>Where the rungs come from</h2>
 * Steps are <strong>one ATR</strong> apart — the stock's own normal daily range, so the ladder is
 * volatility-normalised and a 3% pullback is not treated as identical for a bank and a micro-cap.
 * The deepest rung is snapped to the <strong>50-day average</strong> when that sits nearer than the
 * volatility ladder would reach, because that is the level a "stretched above its average" verdict
 * actually cites, and because <em>a level that is never reached is not a plan</em>. When the average
 * is further away than the ladder, it is not quoted at all.
 *
 * <p>Every level is rounded down to a sensible tick. {@code 424.00} implies a precision nobody has.
 *
 * <p>And a waiting plan always carries its <strong>fallback</strong>: the sentence saying what to do
 * if the dip never comes. That is the one thing an experienced investor says and an amateur never
 * writes down, and omitting it is how a "wait" becomes an accidental decision never to buy.
 */
public final class SuggestedEntry {

    /** Tranches in the ladder. Equal thirds — see the class note on free parameters. */
    private static final int TRANCHES = 3;

    /** Used as the step when ATR could not be measured: a plain equity's typical daily range. */
    private static final double FALLBACK_STEP_PERCENT = 3.0;

    private SuggestedEntry() {
    }

    /**
     * One rung of the ladder.
     *
     * @param price         the level to buy at
     * @param sharePercent  how much of the intended position to place here
     * @param label         short human label ("now", "on a normal pullback", "at the 50-day average")
     */
    public record Rung(double price, int sharePercent, String label) {
    }

    /**
     * @param rungs    the ladder, nearest first; empty when no plan can honestly be given
     * @param basis    short label for a narrow table column ("3 steps from today", "on a dip")
     * @param reason   one plain sentence for the tooltip (SPEC §21)
     * @param fallback what to do if the lower rungs never fill; null for a buy-now plan
     */
    public record Result(List<Rung> rungs, String basis, String reason, String fallback) {

        static Result none(String reason) {
            return new Result(List.of(), null, reason, null);
        }

        /**
         * The nearest rung, or null when there is no plan.
         *
         * <p>Kept so a narrow column, an email row or a stored field can show one number without
         * knowing about the ladder. It is the price the investor would act on <em>first</em>, never
         * an average of the rungs — an average is a price nobody is told to pay.
         */
        public Double price() {
            return rungs.isEmpty() ? null : rungs.get(0).price();
        }

        public boolean isEmpty() {
            return rungs.isEmpty();
        }
    }

    /**
     * @param verdict      the entry verdict shown beside this plan; null is treated as unjudged
     * @param currentPrice last close
     * @param support20d   the 20-day low, or null
     * @param atr14        ATR-14, or null — the ladder's step when known
     * @param ema50        the 50-day average, or null — anchors the deepest rung when it is nearer
     */
    public static Result compute(Verdict verdict, Double currentPrice, Double support20d,
                                 Double atr14, Double ema50) {
        if (currentPrice == null || currentPrice <= 0) {
            return Result.none("No price on the last screening run, so no entry plan can be given.");
        }
        if (verdict == null || verdict == Verdict.NOT_MEASURED) {
            return Result.none("This stock has not been judged, so no entry plan is suggested.");
        }
        if (verdict == Verdict.AVOID) {
            return Result.none("Not a buy at any price on the current reading, so no entry plan "
                    + "is suggested.");
        }

        boolean buyNow = verdict == Verdict.BUY_NOW || verdict == Verdict.ACCUMULATE;
        double step = step(currentPrice, atr14);
        int share = 100 / TRANCHES;

        // Buy verdicts start at market; waiting verdicts start one step down, so a waiting plan
        // can never quote today's price. The contradiction is designed out rather than guarded.
        List<Double> levels = new ArrayList<>();
        for (int i = 0; i < TRANCHES; i++) {
            double raw = currentPrice - step * (buyNow ? i : i + 1);
            levels.add(i == 0 && buyNow ? currentPrice : roundDown(raw));
        }
        anchorDeepestToAverage(levels, currentPrice, ema50, step);

        List<Rung> rungs = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            double p = levels.get(i);
            if (p <= 0 || p > currentPrice) {
                continue; // a level at or above today's price is not a rung of a descending ladder
            }
            // The last rung takes the remainder so the shares always total 100.
            int pct = (i == levels.size() - 1) ? 100 - share * (levels.size() - 1) : share;
            rungs.add(new Rung(p, pct, label(i, buyNow, p, ema50, support20d)));
        }
        if (rungs.isEmpty()) {
            return Result.none("No entry levels could be worked out from the prices on file.");
        }

        return new Result(
                Collections.unmodifiableList(rungs),
                buyNow ? "start now, add lower" : "on a dip, in steps",
                reason(buyNow, rungs, currentPrice),
                buyNow ? null : fallback(rungs.get(0).price()));
    }

    /** Back-compatible three-argument form for callers with no 50-day average to hand. */
    public static Result compute(Verdict verdict, Double currentPrice, Double support20d,
                                 Double atr14) {
        return compute(verdict, currentPrice, support20d, atr14, null);
    }

    /**
     * The stock's own normal daily range, so the ladder means the same thing for a bank and a
     * micro-cap. A fixed percentage would space a volatile stock's rungs inside a single session
     * and a placid one's rungs a year apart.
     */
    private static double step(double currentPrice, Double atr14) {
        if (atr14 != null && atr14 > 0) {
            return atr14;
        }
        return currentPrice * FALLBACK_STEP_PERCENT / 100.0;
    }

    /**
     * Pull the deepest rung up to the 50-day average when the average is nearer than the ladder
     * reaches — that is the level a "stretched above its average" verdict points at, and it is
     * reachable. When the average is further away it is left alone: quoting a level the stock may
     * not revisit for a year is how a plan becomes a decision never to buy.
     */
    private static void anchorDeepestToAverage(List<Double> levels, double currentPrice,
                                               Double ema50, double step) {
        if (ema50 == null || ema50 <= 0 || ema50 >= currentPrice || levels.size() < 2) {
            return;
        }
        int last = levels.size() - 1;
        double anchored = roundDown(ema50);
        double above = levels.get(last - 1);
        // Must sit inside the ladder's last gap, and far enough below the rung above to be a
        // different instruction. Measured on live rows: SKYGOLD's average landed 0.08 of a step
        // under its neighbour (two tranches 0.4% apart - a distinction nobody can act on), while
        // KRBL's sat 0.41 of a step under and is exactly the anchor this feature exists to quote.
        // A quarter of a normal day's range separates the two; half a range wrongly discarded
        // KRBL, which is the failure that matters more.
        boolean farEnoughApart = (above - anchored) >= step / 4.0;
        if (anchored > levels.get(last) && anchored < above && farEnoughApart) {
            levels.set(last, anchored);
        }
    }

    private static String label(int index, boolean buyNow, double price, Double ema50,
                                Double support20d) {
        if (index == 0 && buyNow) {
            return "now";
        }
        if (ema50 != null && ema50 > 0 && Math.abs(roundDown(ema50) - price) < 0.005) {
            return "at the 50-day average";
        }
        if (support20d != null && support20d > 0 && Math.abs(roundDown(support20d) - price) < 0.005) {
            return "at the recent low";
        }
        return index == 0 ? "on a normal pullback" : "if it falls further";
    }

    private static String reason(boolean buyNow, List<Rung> rungs, double currentPrice) {
        StringBuilder sb = new StringBuilder();
        sb.append(buyNow
                ? "Buy it in three parts rather than all at once: "
                : "It is running hot, so buy it in three parts on the way down: ");
        for (int i = 0; i < rungs.size(); i++) {
            Rung r = rungs.get(i);
            if (i > 0) {
                sb.append(i == rungs.size() - 1 ? " and " : ", ");
            }
            sb.append(r.sharePercent()).append("% ").append(r.label().equals("now") ? "now" : "at ")
                    .append(r.label().equals("now") ? "" : money(r.price()));
        }
        sb.append(". Splitting the purchase matters more than the exact price — over a holding "
                + "period of years, a few percent on entry is worth far less than owning the right "
                + "business. Today's price is ").append(money(currentPrice)).append(".");
        return sb.toString();
    }

    /**
     * The sentence an experienced investor says and an amateur never writes down. Without it a
     * "wait" quietly becomes a decision never to buy at all.
     */
    private static String fallback(double firstRung) {
        return "If it has not reached " + money(firstRung) + " in about three months and the "
                + "score still holds, buying anyway beats waiting indefinitely — a dip that never "
                + "comes costs more than the few percent it would have saved.";
    }

    /**
     * Round down to a tick that does not imply false precision. Down, never nearest, so a rounded
     * level can never drift above the price it was derived from.
     */
    static double roundDown(double v) {
        double tick = v >= 2000 ? 5.0 : v >= 500 ? 1.0 : v >= 100 ? 0.5 : 0.05;
        return Math.floor(v / tick) * tick;
    }

    private static String money(double v) {
        return "Rs " + String.format("%,.2f", v);
    }
}
