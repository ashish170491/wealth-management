package com.example.trading.holdings;

/**
 * Reconciles a holding's stored technical signal with the shared buy-timing verdict (SPEC §6.6).
 *
 * <h2>Why this exists</h2>
 * The portfolio table shows two columns side by side that a reader takes as one answer: <em>Signal</em>
 * (from {@code HoldingsAnalysisService.determineRecommendation}) and <em>Still a good time to buy?</em>
 * (from {@link com.example.trading.watchlist.BuyTimingVerdict}). They are computed by different
 * engines from different inputs, and on 2026-09-02 they disagreed on <strong>9 of 32 holdings</strong>
 * — BEL read {@code BUY} beside {@code AVOID}, the AVOID being a forensic red flag on cash conversion.
 *
 * <p>The stored signal is a <strong>momentum rule</strong>: score, trend, RSI and P&amp;L, with no
 * sight of fundamentals, forensic flags or financial quality (B-056 — a rule inherited from the
 * intraday era that the 2026-04-18 pivot never revisited). So it will happily call a stock with a
 * red flag on its books a BUY, because the chart looks fine. That is the B-062 failure — two engines
 * answering one question in one vocabulary — surviving on a surface Gotcha 85 never covered.
 *
 * <h2>The rule</h2>
 * A quality problem vetoes a buy. Precisely:
 * <ul>
 *   <li>{@code AVOID} + a buy signal → <strong>HOLD</strong>. A disqualifying flag means "do not
 *       put more money in", so the app must not print BUY next to it.</li>
 *   <li>{@code HOLD_OFF} + {@code STRONG_BUY} → <strong>BUY</strong>. A caution should not sit
 *       beside the strongest endorsement the app can give.</li>
 *   <li>Everything else is left exactly as it was.</li>
 * </ul>
 *
 * <h2>Two properties that are deliberate</h2>
 * <strong>It only ever lowers a signal.</strong> This is a risk control, and a risk control that can
 * raise a recommendation is not one (Gotcha 42's asymmetry: a bonus being wrong costs an
 * opportunity, a risk control being wrong costs capital).
 *
 * <p><strong>It never says "sell".</strong> "Do not add" is not "get out" — exiting has a tax cost
 * (SPEC §9) and the core-holdings work (§35) exists precisely to stop the investor being shaken out
 * of good businesses. HOLD is the honest floor.
 *
 * <p>Display-only: {@code holdings.recommendation} is never rewritten, so ML labels, stored history
 * and the raw signal are unaffected — the same contract the core overlay keeps (Gotcha 69).
 */
public final class SignalReconciliation {

    private SignalReconciliation() {
    }

    /**
     * @param displaySignal what the Signal column should show
     * @param note          why it differs from the stored signal, or null when it does not
     */
    public record Result(String displaySignal, String note) {
        public boolean adjusted() {
            return note != null;
        }
    }

    /**
     * @param storedSignal  {@code holdings.recommendation} — never modified, only read
     * @param verdict       the buy-timing verdict name, or null when it could not be measured
     * @param verdictReason the verdict's own plain-English reason, quoted back to the reader
     */
    public static Result reconcile(String storedSignal, String verdict, String verdictReason) {
        if (storedSignal == null || storedSignal.isBlank()) {
            return new Result(storedSignal, null);
        }
        // An unmeasured verdict is not a negative one. Silence must never downgrade a signal
        // (Gotcha 21) — that would turn a data gap into investment advice.
        if (verdict == null || verdict.isBlank() || "NOT_MEASURED".equals(verdict)) {
            return new Result(storedSignal, null);
        }
        if (!isBuy(storedSignal)) {
            return new Result(storedSignal, null);
        }

        String tail = (verdictReason == null || verdictReason.isBlank()) ? "" : " " + verdictReason;

        if ("AVOID".equals(verdict)) {
            // The verdict's own reason is quoted verbatim, so this sentence must not restate it -
            // the first draft echoed "a quality problem is not fixed by a good chart" twice in one
            // tooltip, which reads as a machine talking to itself.
            return new Result("HOLD",
                    "The chart says " + human(storedSignal) + ", but the books do not." + tail
                            + " Shown as hold rather than buy. This is not a reason to sell what "
                            + "you already own.");
        }
        if ("HOLD_OFF".equals(verdict) && "STRONG_BUY".equals(storedSignal)) {
            return new Result("BUY",
                    "Softened from strong buy because there is a caution on the books." + tail);
        }
        return new Result(storedSignal, null);
    }

    private static boolean isBuy(String signal) {
        return "BUY".equals(signal) || "STRONG_BUY".equals(signal);
    }

    private static String human(String signal) {
        return "STRONG_BUY".equals(signal) ? "strong buy" : "buy";
    }
}
