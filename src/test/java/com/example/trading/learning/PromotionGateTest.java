package com.example.trading.learning;

import com.example.trading.learning.validation.PromotionGate;
import com.example.trading.learning.validation.Statistics;
import com.example.trading.learning.validation.WalkForwardReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the gate that decides whether the scoring weights may change (SPEC §38.7, §25.5).
 *
 * <p>The most valuable test here is {@link #refusesOnTodaysDataShape()}: it asserts that the
 * data the system actually holds today does <b>not</b> justify a weight change. That is the
 * whole point of the gate, and the failure it exists to prevent — fitting one risk-on quarter
 * and calling it skill — is the failure that ended the previous machine-learning chain.
 *
 * <p>If a change to the gate makes that test pass, the change is the thing to examine, not the
 * test.
 */
class PromotionGateTest {

    /** Blocks with a steady advantage, so significance is not the binding constraint. */
    private static List<Double> steady(int n, double mean) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // Small alternating jitter so the standard deviation is non-zero but modest.
            out.add(mean + (i % 2 == 0 ? 0.004 : -0.004));
        }
        return out;
    }

    private static WalkForwardReport report(int periods, double challengerEffect,
                                            double challengerCoverage, double liveCoverage) {
        List<Double> paired = steady(periods, challengerEffect);
        List<Double> challengerIcs = new ArrayList<>();
        for (double d : paired) challengerIcs.add(0.05 + d);

        WalkForwardReport.VariantResult live = new WalkForwardReport.VariantResult(
                "live", "incumbent", 0.05, 0.01, 5.0, null, null,
                steady(periods, 0.05), liveCoverage, periods);

        WalkForwardReport.VariantResult challenger = new WalkForwardReport.VariantResult(
                "quality-tilt", "fundamentals should dominate",
                Statistics.mean(challengerIcs), Statistics.stdDev(challengerIcs),
                Statistics.tStatistic(challengerIcs),
                Statistics.mean(paired), Statistics.tStatistic(paired),
                challengerIcs, challengerCoverage, periods);

        return new WalkForwardReport(periods, periods * 2, true, 90,
                LocalDate.now().minusDays(400), LocalDate.now().minusDays(90),
                periods * 4, 3, 0, 12, List.of("mb2-abc123"),
                List.of(live, challenger), "test");
    }

    @Test
    @DisplayName("Refuses on the data shape the system actually has: about five periods")
    void refusesOnTodaysDataShape() {
        // Five independent periods, a large apparent advantage, and a long prior run. Even this
        // combination must be refused, because five periods cannot distinguish the advantage
        // from noise however large it looks.
        PromotionGate.Decision d = PromotionGate.evaluate(
                report(5, 0.08, 95, 95), 5,
                List.of(new PromotionGate.PriorReview("quality-tilt", true, 0.08)));

        assertThat(d.eligible()).isFalse();
        assertThat(d.blockers()).anyMatch(b -> b.startsWith("FAIL sample size"));
        assertThat(d.summary()).contains("No weight change is justified");
    }

    @Test
    @DisplayName("A trivially small advantage fails significance even with a long sample")
    void tinyEffectFailsDeflatedSignificance() {
        PromotionGate.Decision d = PromotionGate.evaluate(
                report(16, 0.0005, 95, 95), 5,
                List.of(new PromotionGate.PriorReview("quality-tilt", true, 0.0005)));

        assertThat(d.eligible()).isFalse();
        assertThat(d.blockers()).anyMatch(b -> b.startsWith("FAIL significance"));
    }

    @Test
    @DisplayName("Trying more variants raises the bar for all of them")
    void deflationDependsOnHowManyVariantsWereTried() {
        WalkForwardReport r = report(16, 0.01, 95, 95);

        Double requiredWithOne = PromotionGate.evaluate(r, 1, List.of()).requiredT();
        Double requiredWithTwenty = PromotionGate.evaluate(r, 20, List.of()).requiredT();

        // Best-of-twenty beats an incumbent by construction on a short sample, so the threshold
        // the winner must clear has to grow with the size of the set it was chosen from.
        assertThat(requiredWithTwenty).isGreaterThan(requiredWithOne);
    }

    @Test
    @DisplayName("A first qualifying review fails only on stability, so the run can start")
    void firstReviewFailsOnlyStability() {
        PromotionGate.Decision d = PromotionGate.evaluate(report(16, 0.02, 95, 95), 5, List.of());

        assertThat(d.eligible()).isFalse();
        // Exactly one objection, and it is the one that a second review resolves. If the first
        // review failed on anything else, the consecutive-review requirement could never be
        // satisfied and the gate would be unpassable rather than strict.
        assertThat(d.blockers()).hasSize(1);
        assertThat(d.blockers().get(0)).startsWith("FAIL stability");
    }

    @Test
    @DisplayName("A second consecutive agreeing review satisfies stability")
    void secondConsecutiveReviewPasses() {
        PromotionGate.Decision d = PromotionGate.evaluate(
                report(16, 0.02, 95, 95), 5,
                List.of(new PromotionGate.PriorReview("quality-tilt", true, 0.019)));

        assertThat(d.blockers()).isEmpty();
        assertThat(d.eligible()).isTrue();
        assertThat(d.summary()).contains("Adoption remains manual");
    }

    @Test
    @DisplayName("A run broken by a different winner is not a run")
    void interruptedRunResetsStability() {
        List<PromotionGate.PriorReview> priors = List.of(
                new PromotionGate.PriorReview("momentum-tilt", true, 0.03),
                new PromotionGate.PriorReview("quality-tilt", true, 0.02));

        PromotionGate.Decision d = PromotionGate.evaluate(report(16, 0.02, 95, 95), 5, priors);

        // The most recent review named a different variant, so quality-tilt's earlier showing
        // does not carry forward. Allowing it to would let a variant bank credit across the
        // quarters in which it lost.
        assertThat(d.blockers()).anyMatch(b -> b.startsWith("FAIL stability"));
    }

    @Test
    @DisplayName("A challenger scoring a narrower universe is refused, however well it ranks")
    void narrowerCoverageIsRefused() {
        PromotionGate.Decision d = PromotionGate.evaluate(
                report(16, 0.05, 60, 95), 5,
                List.of(new PromotionGate.PriorReview("quality-tilt", true, 0.05)));

        assertThat(d.eligible()).isFalse();
        assertThat(d.blockers()).anyMatch(b -> b.startsWith("FAIL coverage"));
    }

    @Test
    @DisplayName("Every condition is reported, not only the failures")
    void reasonsCoverAllFiveConditions() {
        PromotionGate.Decision d = PromotionGate.evaluate(report(16, 0.02, 95, 95), 5, List.of());
        // A gate that lists only its objections cannot be audited when it eventually says yes.
        assertThat(d.reasons()).hasSize(5);
    }

    @Test
    @DisplayName("Passing the gate never changes a weight by itself")
    void adoptionIsNeverAutomatic() {
        // The constant is the statement. If this is ever flipped, the scoring engine can become
        // something nobody chose, and its history stops being poolable with its own past.
        assertThat(PromotionGate.AUTOMATIC_ADOPTION_ENABLED).isFalse();
    }

    @Test
    @DisplayName("No challenger means no promotion, and says so rather than erroring")
    void noChallengerIsHandled() {
        WalkForwardReport empty = new WalkForwardReport(0, 0, true, 90, null, null,
                0, 0, 0, 0, List.of(), List.of(), "nothing yet");

        PromotionGate.Decision d = PromotionGate.evaluate(empty, 5, List.of());

        assertThat(d.eligible()).isFalse();
        assertThat(d.variantName()).isNull();
    }
}
