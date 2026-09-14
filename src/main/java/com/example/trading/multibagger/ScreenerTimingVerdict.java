package com.example.trading.multibagger;

import com.example.trading.fundamentals.ForensicSeverity;
import com.example.trading.watchlist.BuyTimingVerdict.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * "Is it still a good time to buy?" for a screener / discovery row (SPEC §12.11).
 *
 * <p>A sibling of {@link com.example.trading.watchlist.BuyTimingVerdict}, sharing its
 * {@link Verdict} vocabulary so one word means one thing across every screen — but deliberately
 * <strong>not</strong> the same rule table, because it does not have the same inputs.
 *
 * <p><strong>Why a separate table rather than reuse.</strong> The watchlist verdict is calibrated on
 * <em>daily</em> RSI-14 and the price's distance from EMA-50. A screening row carries neither: it
 * has <em>weekly</em> RSI, the distance from the 52-week high, and the weekly EMA slope. Feeding a
 * weekly RSI into a rule written for a daily one is a units substitution, the same class of mistake
 * as filing a quarter as a year (B-047) or mixing consolidated with standalone (Gotcha 73) — it
 * produces a number that looks measured and means something else. Measured on the 2026-08-27 run of
 * 294 stocks, weekly RSI ran min 25 / median 54 / p90 67 / max 94, with only 6% above 70; that
 * spread is what the thresholds below are set against, not a textbook daily figure.
 *
 * <p><strong>What this is not.</strong> It is not a price forecast or a target (SPEC §19, §25.3).
 * It answers a narrower question: given what the last screening measured, does the entry look
 * stretched, reasonable, or unattractive right now. Quality and timing are reported separately
 * because a great business at a poor entry and a poor business at a great entry are different
 * situations, and collapsing them into one number hides which is which.
 *
 * <p><strong>Null discipline.</strong> Every input is nullable and null means "not measured", never
 * a neutral value (Gotcha 21). An unmeasured input never fires a rule: a missing RSI is not
 * "not overbought". When the two load-bearing inputs are both absent the verdict is
 * {@link Verdict#NOT_MEASURED} and the reason names what was missing.
 */
public final class ScreenerTimingVerdict {

    private ScreenerTimingVerdict() {
    }

    /** Every field nullable — null means "could not measure". */
    public record Input(
            Integer compositeScore,
            Double weeklyRsi,
            Double priceVs52WeekHigh,
            Double priceVs52WeekLow,
            Double weeklyEmaSlope,
            String forensicFlags,
            String financialQualityVerdict,
            String liquidityTier,
            String dcfVerdict) {
    }

    /**
     * Thresholds, all measured against the live screening distribution rather than assumed.
     *
     * @param rsiStretched      weekly RSI above which an entry is called stretched (top ~6%)
     * @param rsiWeak           weekly RSI below which momentum is absent (bottom ~10%)
     * @param nearHighPercent   within this % of the 52-week high counts as "at the highs"
     * @param deepPullbackPct   this far below the 52-week high counts as a real pullback
     * @param slopeFalling      weekly EMA slope below this means the trend is rolling over
     * @param qualityMinBuy     composite at or above which quality is not the limiting factor
     * @param qualityMinConsider composite below which the entry question is moot
     * @param maxRangePositionForBuy how high in the 52-week range a stock may sit and still be
     *        called a good entry; 70 is the measured median of the 2026-08-27 universe (294 rows:
     *        p10 17 / median 69 / p90 95), i.e. the middle of the market rather than a guess
     */
    public record Thresholds(double rsiStretched, double rsiWeak, double nearHighPercent,
                             double deepPullbackPct, double slopeFalling,
                             int qualityMinBuy, int qualityMinConsider,
                             double maxRangePositionForBuy) {
        public static Thresholds defaults() {
            return new Thresholds(70.0, 38.0, 5.0, 25.0, -1.0, 65, 50, 70.0);
        }
    }

    /**
     * Where the price sits in its 52-week range, 0 (at the low) to 100 (at the high), or null when
     * either leg is unmeasured.
     *
     * <p>"13% below the 12-month high" sounds like a pullback and is not one on its own: a stock
     * can be 13% off its high and still 53% above its low, i.e. it fell and has already bounced
     * most of the way back. Measured on the live 2026-08-27 run, 50 of 56 BUY_NOW rows were in the
     * top half of their range — the rule was calling round-trips pullbacks. Distance-from-high is
     * only half a position; this is the other half.
     */
    static Double rangePosition(Double aboveLowPct, Double belowHighPct) {
        if (aboveLowPct == null || belowHighPct == null) return null;
        double span = aboveLowPct + belowHighPct;
        return span <= 0 ? null : 100.0 * aboveLowPct / span;
    }

    /**
     * @param verdict    the call
     * @param reason     one plain-English sentence, no jargon un-glossed (SPEC §21)
     * @param notMeasured inputs that could not be read; non-empty does not imply NOT_MEASURED
     */
    /**
     * @param rangePosition52w where the price sits in its 52-week range, 0 at the low to 100 at the
     *        high, or null when either leg was unmeasured. Computed by {@link #rangePosition} and
     *        carried out so the screener can show it: before 2026-09-09 it was the discriminator
     *        behind rule 8 and then discarded, leaving the reader with "13% below the high" and no
     *        way to tell a pullback from a round-trip (B-062)
     */
    public record Result(Verdict verdict, String reason, List<String> notMeasured, Double rangePosition52w) {
        public Result(Verdict verdict, String reason, List<String> notMeasured) {
            this(verdict, reason, notMeasured, null);
        }
    }

    public static Result evaluate(Input in) {
        return evaluate(in, Thresholds.defaults());
    }

    public static Result evaluate(Input in, Thresholds t) {
        Result r = decide(in, t);
        return new Result(r.verdict(), r.reason(), r.notMeasured(),
                rangePosition(in.priceVs52WeekLow(), in.priceVs52WeekHigh()));
    }

    private static Result decide(Input in, Thresholds t) {
        List<String> missing = new ArrayList<>();
        if (in.compositeScore() == null) missing.add("quality score (not screened)");
        if (in.weeklyRsi() == null) missing.add("momentum (RSI)");
        if (in.priceVs52WeekHigh() == null) missing.add("distance from the 52-week high");
        if (in.weeklyEmaSlope() == null) missing.add("trend direction");
        if (in.forensicFlags() == null) missing.add("forensic screen");
        if (in.financialQualityVerdict() == null) missing.add("financial quality");

        boolean hasQuality = in.compositeScore() != null;
        boolean hasTiming = in.weeklyRsi() != null || in.priceVs52WeekHigh() != null;

        // 1. Nothing to stand on. Two absent inputs is not a weak verdict, it is no verdict.
        if (!hasQuality && !hasTiming) {
            return new Result(Verdict.NOT_MEASURED,
                    "This stock has not been screened, so there is nothing to judge the entry on.",
                    missing);
        }

        // 2. Hard stops. These are facts about the business, and they settle the question before
        //    any timing rule is consulted — a good entry into a broken balance sheet is not a
        //    good entry. Mirrors the HIGH_RISK composite cap (SPEC §12.5).
        if ("HIGH_RISK".equalsIgnoreCase(in.financialQualityVerdict())) {
            return new Result(Verdict.AVOID,
                    "The balance sheet is flagged high risk, so timing does not come into it.",
                    missing);
        }
        // Severity is respected rather than flattened. The forensic screen grades its own flags
        // HIGH / MEDIUM / INFO and deliberately scores INFO at zero (SPEC §32.4); treating all
        // three as disqualifying would send a 90-score business to AVOID on an informational note.
        String worst = worstSeverity(in.forensicFlags());
        if ("HIGH".equals(worst)) {
            return new Result(Verdict.AVOID,
                    "The accounts raised a serious red flag (" + firstFlagOfSeverity(in.forensicFlags(), "HIGH")
                            + "), which outweighs any entry signal.",
                    missing);
        }
        if ("THIN".equalsIgnoreCase(in.liquidityTier())) {
            return new Result(Verdict.AVOID,
                    "Too little is traded daily to build or exit a position at a fair price.",
                    missing);
        }

        // 3. Quality gate. Below this the entry question is moot — we would not want it cheap.
        if (hasQuality && in.compositeScore() < t.qualityMinConsider()) {
            return new Result(Verdict.AVOID,
                    "Scores " + in.compositeScore() + "/100 on quality, which is too low to buy at "
                            + "any price.",
                    missing);
        }

        // 4. Timing is unreadable but quality is known. Say exactly that rather than guess.
        if (!hasTiming) {
            return new Result(Verdict.NOT_MEASURED,
                    "Quality scores " + in.compositeScore() + "/100, but the entry cannot be judged "
                            + "- no momentum or price-range data on the last screening.",
                    missing);
        }

        // A medium-severity flag is a caution, not a disqualification: it holds the verdict at
        // HOLD_OFF and names the flag, so the reader can see what to check rather than being told
        // only "no".
        if ("MEDIUM".equals(worst)) {
            return new Result(Verdict.HOLD_OFF,
                    "The accounts raised a caution (" + firstFlagOfSeverity(in.forensicFlags(), "MEDIUM")
                            + "). Worth understanding before buying, though it is not disqualifying "
                            + "on its own.",
                    missing);
        }

        Double rsi = in.weeklyRsi();
        Double fromHigh = in.priceVs52WeekHigh();
        Double slope = in.weeklyEmaSlope();
        boolean midQuality = hasQuality && in.compositeScore() < t.qualityMinBuy();

        // 5. Stretched. Note the ordering: being at the highs is only a caution when momentum is
        //    also hot. A stock quietly sitting near its high on moderate RSI is a base, not a
        //    blow-off, and calling that "wait" is how a compounder gets talked out of (B-056).
        if (rsi != null && rsi > t.rsiStretched()) {
            String at = fromHigh != null && fromHigh < t.nearHighPercent()
                    ? " and it is within " + fmt(fromHigh) + "% of its 12-month high" : "";
            return new Result(Verdict.WAIT_FOR_PULLBACK,
                    "Momentum is running hot (weekly RSI " + fmt(rsi) + ", top few % of the market)"
                            + at + ". Better entries usually come after it cools.",
                    missing);
        }

        // 6. Falling knife. Deep below the high AND the trend still rolling over.
        if (fromHigh != null && fromHigh > t.deepPullbackPct()
                && slope != null && slope < t.slopeFalling()) {
            return new Result(Verdict.HOLD_OFF,
                    "It is " + fmt(fromHigh) + "% below its 12-month high and still falling. Wait "
                            + "for the decline to stop before buying.",
                    missing);
        }

        // 7. Weak momentum with nothing turning yet.
        if (rsi != null && rsi < t.rsiWeak() && (slope == null || slope < 0)) {
            return new Result(Verdict.HOLD_OFF,
                    "Momentum is weak (weekly RSI " + fmt(rsi) + ") with no sign of a turn yet.",
                    missing);
        }

        // 8. The pullback case — the one this column exists to surface. Good business, meaningfully
        //    off its high, trend no longer falling, and NOT already back near the top of its range.
        //    The last clause is what stops a round-trip being sold as a pullback: without it the
        //    rule fired on a stock 13% off its high that was still 53% above its low.
        Double rangePos = rangePosition(in.priceVs52WeekLow(), fromHigh);
        boolean extended = rangePos != null && rangePos > t.maxRangePositionForBuy();
        if (hasQuality && !midQuality && fromHigh != null && fromHigh > 10.0
                && (slope == null || slope >= t.slopeFalling()) && !extended) {
            return new Result(Verdict.BUY_NOW,
                    "Quality " + in.compositeScore() + "/100 and it is " + fmt(fromHigh)
                            + "% below its 12-month high without the trend breaking - the kind of "
                            + "pullback worth using.",
                    missing);
        }
        if (extended && hasQuality && !midQuality) {
            return new Result(Verdict.ACCUMULATE,
                    "Quality " + in.compositeScore() + "/100, but the price has already recovered "
                            + "most of the way back up its 12-month range (" + fmt(rangePos)
                            + "% of the way from its low to its high). Buy in tranches rather than "
                            + "treating this as a dip.",
                    missing);
        }

        // 9. Fine to accumulate, with the reason saying which half is the constraint.
        if (midQuality) {
            return new Result(Verdict.ACCUMULATE,
                    "The entry looks reasonable, but quality is middling at " + in.compositeScore()
                            + "/100 - size it smaller than a high-conviction name.",
                    missing);
        }
        String where = fromHigh != null && fromHigh < t.nearHighPercent()
                ? "near its 12-month high, so buy in tranches rather than all at once"
                : "not stretched on any measure we track";
        return new Result(Verdict.ACCUMULATE,
                "Quality " + in.compositeScore() + "/100 and the entry is " + where + ".",
                missing);
    }

    /** Highest severity present, or null when there are no flags. Persisted form is CODE:severity. */
    /**
     * Delegates to {@link ForensicSeverity} — the single parser both buy-timing engines share, so
     * a severity rule can never be fixed on one screen and left broken on the other (B-065).
     * An unrecognised token grades as a caution rather than as INFO: unknown is not "nothing".
     */
    private static String worstSeverity(String flags) {
        ForensicSeverity.Level l = ForensicSeverity.worst(flags);
        return switch (l) {
            case NONE -> null;
            case HIGH -> "HIGH";
            case MEDIUM, UNKNOWN -> "MEDIUM";
            case INFO -> "INFO";
        };
    }

    /** The first flag code at the given severity, without its severity suffix. */
    private static String firstFlagOfSeverity(String flags, String severity) {
        ForensicSeverity.Level level = "HIGH".equals(severity)
                ? ForensicSeverity.Level.HIGH : ForensicSeverity.Level.MEDIUM;
        String named = ForensicSeverity.firstAt(flags, level);
        if (named == null && level == ForensicSeverity.Level.MEDIUM) {
            named = ForensicSeverity.firstAt(flags, ForensicSeverity.Level.UNKNOWN);
        }
        return named != null ? named : (flags == null ? "" : flags.trim());
    }

    private static String fmt(double d) {
        return String.format("%.0f", d);
    }
}
