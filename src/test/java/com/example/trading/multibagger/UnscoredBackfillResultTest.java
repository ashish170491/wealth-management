package com.example.trading.multibagger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the contract of the "score the ones we have not scored" pass (SPEC §12.13).
 *
 * <p>The property that matters is the one the record exists for: <b>"has no score" is four
 * different facts</b>, and only one of them is a gap in the app. A stock the small/micro-cap
 * quality gate discarded was measured and then excluded by a rule — re-running rejects it again —
 * while a stock that failed is one the app could not measure at all. Collapsing them would
 * re-screen ~86 deliberately-excluded names on every pass, burn paced broker calls reaching the
 * same verdict, and report a permanent "gap" that is actually a decision (Gotcha 44, Gotcha 68).
 *
 * <p>Pure: no Spring context, no repository, no broker.
 */
class UnscoredBackfillResultTest {

    private static UnscoredBackfillResult result(List<UnscoredBackfillResult.Scored> scored,
                                                 List<String> rejected, List<String> failed,
                                                 List<String> skipped) {
        return new UnscoredBackfillResult(LocalDate.of(2026, 9, 19), 405, 279,
                scored.size() + rejected.size() + failed.size(),
                scored, rejected, failed, skipped, "note");
    }

    private static UnscoredBackfillResult.Scored scored(String symbol, int composite) {
        return new UnscoredBackfillResult.Scored(symbol, composite, "POTENTIAL_MULTIBAGGER", "A",
                72.5, true);
    }

    @Test
    @DisplayName("a rejected stock is never counted as scored, and never as failed")
    void rejectedIsItsOwnOutcome() {
        var r = result(List.of(scored("NSE:KAYNES", 79)), List.of("NSE:TINYCO"),
                List.of("NSE:BROKENCO"), List.of());

        assertThat(r.scoredCount()).isEqualTo(1);
        assertThat(r.tierRejected()).containsExactly("NSE:TINYCO");
        assertThat(r.failed()).containsExactly("NSE:BROKENCO");
        // Three distinct lists that never share a symbol — the whole point of the record.
        assertThat(r.tierRejected()).doesNotContainAnyElementsOf(r.failed());
        assertThat(r.scored()).extracting(UnscoredBackfillResult.Scored::symbol)
                .doesNotContainAnyElementsOf(r.tierRejected());
    }

    @Test
    @DisplayName("the caveat says a rejection is a decision, not a gap")
    void caveatDistinguishesDecisionFromGap() {
        // Without this sentence "39 scored, 86 rejected" reads as 86 failures of the app.
        String c = result(List.of(), List.of("A"), List.of(), List.of()).caveat();
        assertThat(c).contains("decision, not a gap");
        assertThat(c).contains("reject it again");
        assertThat(c).contains("Only the failed list is a gap");
    }

    @Test
    @DisplayName("scoring a stock is never presented as a view on owning it")
    void scoringIsNotApproval() {
        // SPEC §20 rule 10. This pass publishes rows, and a row is a measurement — the moment the
        // wording implies otherwise it has become a recommendation surface.
        String c = result(List.of(), List.of(), List.of(), List.of()).caveat().toLowerCase();
        assertThat(c).contains("never a view that the stock is worth owning");
        for (String verb : List.of("buy", "sell", "accumulate")) {
            assertThat(c).doesNotMatch("(?s).*\\b" + verb + "\\b.*");
        }
    }

    @Test
    @DisplayName("an empty pass still reports its denominators")
    void emptyPassStillCarriesContext() {
        // A bare "nothing to do" is indistinguishable from a pass that failed to run at all.
        var r = result(List.of(), List.of(), List.of(), List.of());
        assertThat(r.universeSize()).isEqualTo(405);
        assertThat(r.alreadyScored()).isEqualTo(279);
        assertThat(r.screeningDate()).isNotNull();
    }

    @Test
    @DisplayName("not-reached symbols are reported, so a bounded pass is visibly incomplete")
    void skippedIsVisible() {
        // The pass stops at a limit or a deadline. A run that silently stopped early would look
        // exactly like one that found nothing left to do.
        var r = result(List.of(scored("NSE:A", 70)), List.of(), List.of(), List.of("NSE:B", "NSE:C"));
        assertThat(r.skipped()).containsExactly("NSE:B", "NSE:C");
        assertThat(r.examined()).isEqualTo(1);
    }
}
