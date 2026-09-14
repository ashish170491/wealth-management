package com.example.trading.holdings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.trading.holdings.SignalReconciliation.Result;
import static com.example.trading.holdings.SignalReconciliation.reconcile;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §6.6 — the Signal column must not contradict the buy-timing column beside it.
 *
 * <p>The investor found this on BEL: {@code BUY} printed next to {@code AVOID}, the AVOID being a
 * forensic red flag on cash conversion. Nine of thirty-two holdings disagreed the same way. These
 * cases pin the veto and, just as importantly, pin what it must <em>not</em> do.
 */
class SignalReconciliationTest {

    private static final String AVOID_REASON =
            "Red flag on the books: cash conversion. Quality problems are not fixed by a good chart.";

    // ------------------------------------------------------------ the veto

    /** BEL, exactly as it appeared on the portfolio page. */
    @Test
    @DisplayName("BEL: a buy signal beside AVOID is shown as hold, and says why")
    void avoidVetoesBuy() {
        Result r = reconcile("BUY", "AVOID", AVOID_REASON);
        assertThat(r.displaySignal()).isEqualTo("HOLD");
        assertThat(r.adjusted()).isTrue();
        assertThat(r.note()).contains("cash conversion");
        assertThat(r.note()).contains("not a reason to sell");
    }

    @Test
    @DisplayName("AVOID vetoes a strong buy too")
    void avoidVetoesStrongBuy() {
        assertThat(reconcile("STRONG_BUY", "AVOID", AVOID_REASON).displaySignal()).isEqualTo("HOLD");
    }

    /** A caution should not sit beside the strongest endorsement the app can give. */
    @Test
    @DisplayName("HOLD_OFF softens a strong buy to buy")
    void holdOffSoftensStrongBuy() {
        Result r = reconcile("STRONG_BUY", "HOLD_OFF", "The accounts carry a caution (receivables).");
        assertThat(r.displaySignal()).isEqualTo("BUY");
        assertThat(r.note()).contains("caution");
    }

    @Test
    @DisplayName("HOLD_OFF leaves a plain buy alone — a caution is not a disqualification")
    void holdOffLeavesPlainBuy() {
        Result r = reconcile("BUY", "HOLD_OFF", "The accounts carry a caution (receivables).");
        assertThat(r.displaySignal()).isEqualTo("BUY");
        assertThat(r.adjusted()).isFalse();
    }

    // ------------------------------------------------------------ what it must not do

    /**
     * The veto is a risk control, so it may only ever lower a signal. One that could raise a
     * recommendation would not be a risk control (Gotcha 42).
     */
    @Test
    @DisplayName("It never raises a signal, for any combination")
    void neverRaisesASignal() {
        int[] ranks = {0, 1, 2, 3, 4};
        String[] signals = {"STRONG_SELL", "SELL", "HOLD", "BUY", "STRONG_BUY"};
        String[] verdicts = {"BUY_NOW", "ACCUMULATE", "WAIT_FOR_PULLBACK", "HOLD_OFF", "AVOID",
                "NOT_MEASURED", null};
        for (int i = 0; i < signals.length; i++) {
            for (String v : verdicts) {
                String shown = reconcile(signals[i], v, "because").displaySignal();
                int shownRank = rank(shown, signals);
                assertThat(shownRank).as("%s + %s became %s", signals[i], v, shown)
                        .isLessThanOrEqualTo(ranks[i]);
            }
        }
    }

    /** "Do not add" is not "get out": exiting costs tax, and §35 exists to prevent shake-outs. */
    @Test
    @DisplayName("It never turns anything into a sell")
    void neverSells() {
        for (String s : new String[]{"BUY", "STRONG_BUY", "HOLD", "BOOK_PROFIT"}) {
            for (String v : new String[]{"AVOID", "HOLD_OFF", "WAIT_FOR_PULLBACK"}) {
                assertThat(reconcile(s, v, "because").displaySignal())
                        .as("%s + %s", s, v).isNotIn("SELL", "STRONG_SELL");
            }
        }
    }

    /** An unmeasured verdict is silence, not a negative. Turning it into one is Gotcha 21. */
    @Test
    @DisplayName("An unmeasured or missing verdict changes nothing")
    void unmeasuredChangesNothing() {
        for (String v : new String[]{null, "", "NOT_MEASURED"}) {
            Result r = reconcile("STRONG_BUY", v, null);
            assertThat(r.displaySignal()).as("verdict %s", v).isEqualTo("STRONG_BUY");
            assertThat(r.adjusted()).isFalse();
        }
    }

    @Test
    @DisplayName("A positive verdict leaves a buy untouched")
    void positiveVerdictLeavesBuyAlone() {
        for (String v : new String[]{"BUY_NOW", "ACCUMULATE", "WAIT_FOR_PULLBACK"}) {
            assertThat(reconcile("BUY", v, "fine").adjusted()).as("%s", v).isFalse();
        }
    }

    @Test
    @DisplayName("Sells and holds are never touched, whatever the verdict says")
    void nonBuySignalsUntouched() {
        for (String s : new String[]{"SELL", "STRONG_SELL", "HOLD", "BOOK_PROFIT"}) {
            for (String v : new String[]{"AVOID", "HOLD_OFF", "BUY_NOW"}) {
                Result r = reconcile(s, v, "because");
                assertThat(r.displaySignal()).as("%s + %s", s, v).isEqualTo(s);
                assertThat(r.adjusted()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("A missing stored signal is left missing, not invented")
    void missingStoredSignalStaysMissing() {
        assertThat(reconcile(null, "AVOID", AVOID_REASON).displaySignal()).isNull();
        assertThat(reconcile("", "AVOID", AVOID_REASON).displaySignal()).isEmpty();
    }

    /** Every adjustment explains itself — a silent downgrade is a different kind of confusion. */
    @Test
    @DisplayName("Any adjustment carries a reason")
    void adjustmentsExplainThemselves() {
        assertThat(reconcile("BUY", "AVOID", AVOID_REASON).note()).isNotBlank();
        assertThat(reconcile("STRONG_BUY", "HOLD_OFF", "caution").note()).isNotBlank();
        // and a reason-less verdict still produces a usable sentence
        assertThat(reconcile("BUY", "AVOID", null).note()).isNotBlank();
    }

    private static int rank(String signal, String[] order) {
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(signal)) return i;
        }
        return -1;
    }
}
