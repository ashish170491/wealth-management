package com.example.trading.watchlist;

import com.example.trading.fundamentals.ForensicSeverity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Is it still a good time to buy?" — SPEC §37.3.
 *
 * <p><b>One rule table, three surfaces.</b> The watchlist asks it of a stock being considered,
 * the holdings screen asks it of a stock already owned ("should I add more?", SPEC §6.5), and the
 * screener defers to the watchlist's answer for any tracked stock. They share this function
 * precisely so the same stock cannot be given two different answers in the same six words
 * (Gotcha 81, B-062). The caller supplies whichever row it has; the vocabulary of
 * {@code entrySignal} may be the watchlist's (STRONG_BUY/BUY/HOLD/AVOID) or a holding's
 * recommendation (…/SELL/STRONG_SELL), and both are understood here rather than translated by
 * the caller — a mapping step is where a SELL quietly becomes a HOLD.
 *
 * <p>Pure, deterministic, first-match-wins. Two separate questions feed it and are never
 * blended into one number: <b>quality</b> ("is this a business worth owning for years?", the
 * multibagger composite from the screening) and <b>timing</b> ("is today a sensible day to pay
 * this price?", the watchlist technical analysis). A strong business at an extended price is
 * WAIT, not BUY; a weak business with a pretty chart is AVOID.
 *
 * <p>Null discipline (Gotcha 21/68): an unmeasured input never triggers a rule. A missing
 * forensic flag is not a clean bill, and a missing RSI is not "not overbought" — both simply
 * fail to fire and are listed in {@code notMeasured}. The verdict is computed at read time and
 * never persisted on the watchlist row, so the rule table can change without a backfill.
 */
public final class BuyTimingVerdict {

    private BuyTimingVerdict() {}

    public enum Verdict { BUY_NOW, ACCUMULATE, WAIT_FOR_PULLBACK, HOLD_OFF, AVOID, NOT_MEASURED }

    /** Every field nullable — null means "could not measure", never a default. */
    public record Input(
            Integer qualityScore,
            String entrySignal,
            String signalReason,
            Double rsi14,
            Double currentPrice,
            Double ema50,
            String trendDirection,
            String decayVerdict,
            Integer decayDelta30,
            String forensicFlags,
            String financialQualityVerdict,
            String liquidityTier,
            String dcfVerdict,
            Double returnSinceAddPct) {}

    public record Thresholds(int qualityMinBuy, int qualityMinHold, double rsiOverbought,
                             double runawayReturnPct, double runawayRsi, double stretchAboveEma50Pct) {
        public static Thresholds defaults() {
            return new Thresholds(65, 50, 70.0, 15.0, 60.0, 10.0);
        }

        public static Thresholds from(WatchlistConfig.VerdictConfig c) {
            return new Thresholds(c.getQualityMinBuy(), c.getQualityMinHold(), c.getRsiOverbought(),
                    c.getRunawayReturnPct(), c.getRunawayRsi(), c.getStretchAboveEma50Pct());
        }
    }

    public record Result(Verdict verdict, String reason, boolean qualityMeasured,
                         boolean timingMeasured, List<String> notMeasured) {}

    public static Result evaluate(Input in, Thresholds t) {
        List<String> missing = new ArrayList<>();
        boolean qualityMeasured = in.qualityScore() != null;
        boolean timingMeasured = in.entrySignal() != null;

        if (!qualityMeasured) missing.add("quality score (not yet screened)");
        if (!timingMeasured) missing.add("technical analysis");
        if (in.rsi14() == null) missing.add("RSI");
        if (in.forensicFlags() == null) missing.add("forensic screen");
        if (in.financialQualityVerdict() == null) missing.add("financial quality");
        if (in.liquidityTier() == null) missing.add("liquidity");
        if (in.dcfVerdict() == null) missing.add("valuation (reverse DCF)");
        if (in.decayVerdict() == null || "NO_DATA".equals(in.decayVerdict()) || "STALE".equals(in.decayVerdict())) {
            missing.add("score trend (30/60 days)");
        }
        if (in.returnSinceAddPct() == null) missing.add("return since added");

        String signal = upper(in.entrySignal());
        String q = in.qualityScore() == null ? "?" : String.valueOf(in.qualityScore());
        String decayNote = "WATCH".equals(upper(in.decayVerdict())) && in.decayDelta30() != null
                ? String.format(" — note: score slipped %+d in 30 days", in.decayDelta30()) : "";

        // 1. Nothing to go on.
        if (!qualityMeasured && !timingMeasured) {
            return result(Verdict.NOT_MEASURED,
                    "Not analysed yet — press Refresh, or wait for the 11:00 run. No quality score either: "
                            + "the stock is not in the screening universe or has not been screened yet.",
                    false, false, missing);
        }

        // 2. Red flags on the books beat any chart — but severity is read, not just presence.
        //    The forensic screen grades its own flags HIGH / MEDIUM / INFO and scores INFO at zero
        //    (SPEC §32.4, Gotcha 77). Flattening the tiers told the investor to AVOID three
        //    holdings on a medium receivables note while the screener called the same stocks a
        //    caution — one string, two answers (B-065).
        ForensicSeverity.Level worst = ForensicSeverity.worst(in.forensicFlags());
        if ("HIGH_RISK".equals(upper(in.financialQualityVerdict()))) {
            return result(Verdict.AVOID,
                    "Red flag on the books: financial quality HIGH_RISK. Quality problems are not fixed by a good chart.",
                    qualityMeasured, timingMeasured, missing);
        }
        if (worst == ForensicSeverity.Level.HIGH) {
            return result(Verdict.AVOID,
                    "Red flag on the books: " + ForensicSeverity.firstAt(in.forensicFlags(), worst)
                            + ". Quality problems are not fixed by a good chart.",
                    qualityMeasured, timingMeasured, missing);
        }

        // 3. Cannot be bought or sold sensibly.
        if ("THIN".equals(upper(in.liquidityTier()))) {
            return result(Verdict.AVOID, "Too thinly traded to build or exit a position sensibly.",
                    qualityMeasured, timingMeasured, missing);
        }

        // 4. Business does not clear the bar.
        if (qualityMeasured && in.qualityScore() < t.qualityMinHold()) {
            return result(Verdict.AVOID,
                    "Composite " + q + " is below " + t.qualityMinHold() + " — the business does not clear the quality bar.",
                    qualityMeasured, timingMeasured, missing);
        }

        // 5. Thesis decaying.
        String decay = upper(in.decayVerdict());
        if ("BROKEN".equals(decay) || "DECAYING".equals(decay)) {
            String delta = in.decayDelta30() != null ? String.format("%+d", in.decayDelta30()) : "sharply";
            return result(Verdict.HOLD_OFF, "Thesis is decaying: composite moved " + delta
                    + " against the rest of the universe in 30 days. Wait for it to stabilise.",
                    qualityMeasured, timingMeasured, missing);
        }

        // 5b. A medium or unrecognised flag is a caution, not a disqualification: it holds the
        //     verdict and names what to check, rather than saying only "no".
        if (worst == ForensicSeverity.Level.MEDIUM || worst == ForensicSeverity.Level.UNKNOWN) {
            String named = ForensicSeverity.firstAt(in.forensicFlags(), worst);
            return result(Verdict.HOLD_OFF,
                    "The accounts carry a caution (" + named + ") — worth checking before you add, "
                            + "though it is not disqualifying on its own." + decayNote,
                    qualityMeasured, timingMeasured, missing);
        }

        // 6. Price already assumes too much.
        if ("EXTREMELY_EXPENSIVE".equals(upper(in.dcfVerdict()))) {
            return result(Verdict.HOLD_OFF, "Price already assumes far more growth than the business has delivered.",
                    qualityMeasured, timingMeasured, missing);
        }

        // 7. The signal itself says no, or the trend is against you. A holding's SELL /
        //    STRONG_SELL is a first-class "no" here: it must never fall through to a later rule
        //    and come back out as ACCUMULATE.
        boolean signalSaysNo = "AVOID".equals(signal) || "SELL".equals(signal) || "STRONG_SELL".equals(signal);
        if (signalSaysNo || "BEARISH".equals(upper(in.trendDirection()))) {
            String why = in.signalReason() != null && !in.signalReason().isBlank() ? ": " + in.signalReason().trim() : ".";
            String head = ("SELL".equals(signal) || "STRONG_SELL".equals(signal))
                    ? "The analysis rates it " + humanise(signal) + " — not a time to add"
                    : "Trend is down — a good business at a bad time";
            return result(Verdict.HOLD_OFF, head + why + decayNote,
                    qualityMeasured, timingMeasured, missing);
        }

        // 8. It has run away from your entry. Worded to read correctly on both surfaces: the
        //    watchlist measures from the price when it was added, a holding from its average cost.
        if (in.returnSinceAddPct() != null && in.rsi14() != null
                && in.returnSinceAddPct() > t.runawayReturnPct() && in.rsi14() > t.runawayRsi()) {
            return result(Verdict.WAIT_FOR_PULLBACK,
                    String.format("Up %.1f%% on your entry and RSI %.0f — it has run; wait for a dip.",
                            in.returnSinceAddPct(), in.rsi14()) + decayNote,
                    qualityMeasured, timingMeasured, missing);
        }

        // 9. Overbought.
        if (in.rsi14() != null && in.rsi14() > t.rsiOverbought()) {
            return result(Verdict.WAIT_FOR_PULLBACK,
                    String.format("RSI %.0f is overbought — wait for it to cool below 60.", in.rsi14()) + decayNote,
                    qualityMeasured, timingMeasured, missing);
        }

        // 10. Stretched above the 50-day average.
        if (in.currentPrice() != null && in.ema50() != null && in.ema50() > 0
                && in.currentPrice() > in.ema50() * (1 + t.stretchAboveEma50Pct() / 100.0)) {
            double above = (in.currentPrice() - in.ema50()) / in.ema50() * 100.0;
            return result(Verdict.WAIT_FOR_PULLBACK,
                    String.format("Price is %.0f%% above its 50-day average — stretched; wait for a dip nearer the average.", above) + decayNote,
                    qualityMeasured, timingMeasured, missing);
        }

        boolean timingBuy = "STRONG_BUY".equals(signal) || "BUY".equals(signal);

        // 11. Timing known, quality never measured.
        if (!qualityMeasured) {
            String base = "Timing is " + humanise(signal) + " but no quality score exists — the stock is not in the "
                    + "screening universe, so the business case is unmeasured.";
            return timingBuy
                    ? result(Verdict.ACCUMULATE, base + " Small tranches only." + decayNote, false, true, missing)
                    : result(Verdict.HOLD_OFF, base + decayNote, false, true, missing);
        }

        int quality = in.qualityScore();

        // 12–13. Good business.
        if (quality >= t.qualityMinBuy()) {
            if (timingBuy) {
                return result(Verdict.BUY_NOW,
                        "Quality " + q + " and a " + humanise(signal) + " technical entry — both halves agree." + decayNote,
                        true, true, missing);
            }
            return result(Verdict.ACCUMULATE,
                    "Good business (" + q + ") but no entry trigger yet — add in small tranches, not all at once." + decayNote,
                    true, timingMeasured, missing);
        }

        // 14. Middling business, good timing.
        if (timingBuy) {
            return result(Verdict.ACCUMULATE,
                    "Timing is right but quality " + q + " is middling — small position only." + decayNote,
                    true, true, missing);
        }

        // 15. Nothing lines up.
        return result(Verdict.HOLD_OFF,
                "Neither the business (" + q + ") nor the chart is compelling right now." + decayNote,
                true, timingMeasured, missing);
    }

    /**
     * First forensic flag token that is not merely informational, or null when none / unmeasured.
     *
     * <p>Retained for callers that only need "is there anything to look at". The verdict itself
     * grades by {@link ForensicSeverity} — presence alone is not a decision (B-065).
     */
    static String firstRedFlag(String flags) {
        if (flags == null || flags.isBlank()) return null;
        for (String token : flags.split("[,;|]")) {
            String f = token.trim();
            if (f.isEmpty()) continue;
            String u = f.toUpperCase(Locale.ROOT);
            if (u.equals("NONE") || u.equals("CLEAN") || u.startsWith("INFO")) continue;
            if (ForensicSeverity.severityOf(f) == ForensicSeverity.Level.INFO) continue;
            return f;
        }
        return null;
    }

    private static Result result(Verdict v, String reason, boolean qm, boolean tm, List<String> missing) {
        return new Result(v, reason, qm, tm, List.copyOf(missing));
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String humanise(String enumish) {
        if (enumish == null) return "unknown";
        return enumish.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
