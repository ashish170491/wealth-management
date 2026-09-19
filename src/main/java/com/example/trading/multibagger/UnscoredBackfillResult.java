package com.example.trading.multibagger;

import java.time.LocalDate;
import java.util.List;

/**
 * What one "score the ones we have not scored" pass actually did (SPEC §12.13).
 *
 * <p><b>Four outcomes, never two.</b> The whole value of this record is that it refuses to let
 * "has no score" mean one thing. A universe symbol without a row on the latest screening date got
 * there by one of four routes, and they call for opposite responses:
 *
 * <ul>
 *   <li>{@code scored} — it had never been measured, and now it has been. The real backfill.</li>
 *   <li>{@code tierRejected} — it <b>was</b> measured, by this pass and by every full run before
 *       it, and the small/micro-cap quality gate discarded the result. Not a gap. Re-running will
 *       discard it again, which is why these are reported rather than retried.</li>
 *   <li>{@code failed} — the data could not be fetched or parsed. A gap in the app, and the one
 *       bucket worth investigating.</li>
 *   <li>{@code skipped} — the pass ran out of its limit or its deadline before reaching it.</li>
 * </ul>
 *
 * <p>Collapsing the first two is the failure this record exists to prevent: it would re-screen
 * ~86 deliberately-excluded stocks on every run, burn paced broker calls to reach the same verdict,
 * and report a permanent "gap" that is actually a decision (Gotcha 44, Gotcha 68).
 *
 * @param screeningDate the date the new rows were written under
 * @param universeSize  every symbol the screening run would cover
 * @param alreadyScored symbols that already had a row on that date — the pass did not touch them
 */
public record UnscoredBackfillResult(
        LocalDate screeningDate,
        int universeSize,
        int alreadyScored,
        int examined,
        List<Scored> scored,
        List<String> tierRejected,
        List<String> failed,
        List<String> skipped,
        String note) {

    /** One newly-measured stock, with enough to judge it without a second query. */
    public record Scored(String symbol, Integer compositeScore, String verdict, String grade,
                         Double percentileRank, boolean candidate) {
    }

    public int scoredCount() {
        return scored.size();
    }

    /**
     * The sentence that has to travel with the counts.
     *
     * <p>Without it a reader sees "39 scored, 86 rejected" and concludes the app failed on 86
     * stocks. It did not: it measured them and applied a rule. The distinction is the same one
     * §51.3 draws between a coverage gap and a decision.
     */
    public String caveat() {
        return "A stock counted as rejected was measured and then discarded by the small and "
                + "micro-cap quality gate — the same gate the daily run applies, so re-running "
                + "this will reject it again. That is a decision, not a gap. Only the failed "
                + "list is a gap in the app. Scoring a stock means it has now been measured; it "
                + "is never a view that the stock is worth owning.";
    }
}
