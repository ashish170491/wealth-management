package com.example.trading.learning.validation;

import com.example.trading.learning.WeightVariant;

import java.util.ArrayList;
import java.util.List;

/**
 * The mechanical test a weight variant must pass before it may replace the live vector
 * (SPEC §38.7, step 3).
 *
 * <p><b>What this codifies.</b> SPEC §25.5 has said "measure first, tune later" since the day
 * the previous machine-learning chain was deleted, and the condition for reopening the question
 * has been a paragraph of prose that a person applies. Prose is not a gate: it can be
 * reinterpreted by whoever is keen to ship, and the reinterpretation always happens in the
 * direction of shipping. This class is the same judgement written as arithmetic, so that
 * changing the bar requires editing a constant with a comment attached, in a commit, rather
 * than deciding in the moment that the data looks good enough.
 *
 * <p><b>It is designed to fail today, and for some time.</b> With roughly five independent
 * periods every check below returns a refusal, and that is the correct output. A gate that
 * could be passed with the data currently in hand would be a gate that certifies one risk-on
 * quarter as skill, which is precisely the failure the whole substrate was built to prevent.
 * If a future change makes this pass sooner, the change is the thing to examine.
 *
 * <p><b>Five independent conditions</b>, each of which has a specific past failure behind it:
 * <ol>
 *   <li><b>Sample size in periods, not rows.</b> The row count reached 8,933 while the honest
 *       sample was about five (SPEC §25.5).</li>
 *   <li><b>Deflated significance.</b> The threshold is tightened by the number of variants
 *       tried. Best-of-five beats an incumbent by construction on a short sample.</li>
 *   <li><b>A positive paired effect.</b> Measured against the live vector on the same stocks
 *       and dates, so the market move common to both cancels.</li>
 *   <li><b>Stability across two consecutive reviews.</b> A single review that clears the bar is
 *       a result; the same result again a quarter later is evidence. This is the condition
 *       {@code recommendation_outcomes} could never satisfy for the deleted models, because
 *       nothing recorded what the previous review had said.</li>
 *   <li><b>Comparable coverage.</b> A variant that scores only the well-documented third of the
 *       universe is not beating the live vector, it is answering an easier question — the same
 *       error as reading a dimension IC without its coverage row (SPEC §38.2).</li>
 * </ol>
 *
 * <p>Pure — no Spring, no I/O. It decides nothing on its own: it returns a verdict that a human
 * acts on. Nothing in this codebase adjusts a weight automatically, and
 * {@link #AUTOMATIC_ADOPTION_ENABLED} exists to state that in code rather than only in prose.
 */
public final class PromotionGate {

    /**
     * Whether passing this gate causes the live weights to change by itself.
     *
     * <p>Permanently false, and load-bearing. Even a fully passed gate produces a recommendation
     * for a person to accept, because the failure mode of automatic adoption is not a bad
     * quarter — it is a scoring engine that silently becomes something nobody chose, and whose
     * history can no longer be pooled with its own past (SPEC §38.1). Constrained, capped,
     * shrink-toward-current re-weighting is the eventual step, and it is still blocked on
     * 180-day outcomes that do not exist yet.
     */
    public static final boolean AUTOMATIC_ADOPTION_ENABLED = false;

    /**
     * Non-overlapping periods required before a difference is considered at all.
     *
     * <p>Twelve is a lenient reading of what a t-test over block means needs, not a strict one:
     * detecting a rank-correlation improvement of 0.03 with a between-block spread of 0.05
     * needs about seventeen. It is set here at twelve to leave room for a genuinely large
     * effect to be recognised sooner, and no lower, because below about ten the standard error
     * is estimated too poorly for the t-statistic to mean what it says.
     *
     * <p>Consequence, stated plainly: at a 30-day horizon with the embargo on, twelve periods is
     * roughly two years of screening history, and at 180 days it is several. That is not the
     * gate being pessimistic. It is how long it takes to learn something durable about a
     * multi-year holding decision, and the honest alternative to waiting is not a shortcut, it
     * is a different and false answer.
     */
    public static final int MIN_INDEPENDENT_PERIODS = 12;

    /** Family-wise significance level, before deflation by the number of variants tried. */
    public static final double BASE_ALPHA = 0.05;

    /** Consecutive passing reviews required. One is a result; two is evidence. */
    public static final int REQUIRED_CONSECUTIVE_REVIEWS = 2;

    /**
     * A challenger must rank at least this share of the cross-section the live vector ranks.
     *
     * <p>Set at 90%: below that the two are not scoring the same universe, and the challenger's
     * advantage may be nothing more than having declined to score the hard cases.
     */
    public static final double MIN_RELATIVE_COVERAGE = 0.90;

    private PromotionGate() {
    }

    /**
     * What a prior review concluded, for the stability check.
     *
     * <p><b>{@code passed} means "met every condition except stability".</b> It cannot mean
     * fully eligible, or the requirement could never be satisfied: the first qualifying review
     * necessarily fails stability for want of a predecessor, so recording it as a failure would
     * reset the run every time and no variant could ever accumulate two consecutive passes. The
     * stability condition asks whether the other four conditions held twice running, which is a
     * question about the evidence rather than about the gate's own verdict.
     *
     * @param variantName  the challenger that led that review
     * @param passed       whether every condition other than stability was met at that time
     * @param meanIcVsLive the paired effect measured then; must still be positive to count
     */
    public record PriorReview(String variantName, boolean passed, Double meanIcVsLive) {
    }

    /**
     * The gate's answer.
     *
     * @param eligible      true only when every condition is met. Even then, adoption is manual
     * @param variantName   the challenger evaluated, or null when there was no candidate
     * @param reasons       every condition, passed or failed, in the order checked — a gate that
     *                      reports only its objections cannot be audited when it says yes
     * @param blockers      the subset that failed; empty when eligible
     * @param requiredT     the deflated critical t-value the effect had to clear
     * @param observedT     the paired t-statistic actually measured, or null
     */
    public record Decision(boolean eligible,
                           String variantName,
                           List<String> reasons,
                           List<String> blockers,
                           Double requiredT,
                           Double observedT,
                           String summary) {
    }

    /**
     * Evaluate the leading challenger in a walk-forward report.
     *
     * @param report        the evaluation to judge
     * @param variantsTried how many alternatives are in the pre-registered set — the deflation
     *                      factor. Passing 1 here would be the single easiest way to make this
     *                      gate lie, so it is taken from the registry, never assumed
     * @param priorReviews  earlier reviews, most recent first
     */
    public static Decision evaluate(WalkForwardReport report, int variantsTried,
                                    List<PriorReview> priorReviews) {
        List<String> reasons = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        if (report == null || report.variants() == null || report.variants().isEmpty()) {
            return new Decision(false, null, List.of(), List.of("No evaluation was produced."),
                    null, null, "No walk-forward evaluation to judge.");
        }

        // The challenger is the alternative with the largest paired advantage over live. Chosen
        // by result rather than in advance, which is exactly why the threshold below is deflated
        // by the size of the set it was chosen from.
        WalkForwardReport.VariantResult best = null;
        for (WalkForwardReport.VariantResult v : report.variants()) {
            if (WeightVariant.LIVE.equals(v.name())) continue;
            if (v.meanIcVsLive() == null) continue;
            if (best == null || v.meanIcVsLive() > best.meanIcVsLive()) best = v;
        }
        if (best == null) {
            return new Decision(false, null, List.of(),
                    List.of("No alternative produced a paired comparison against the live vector."),
                    null, null,
                    "Nothing to promote: no challenger was measurable alongside the live weights.");
        }

        // 1. Sample size, counted in independent periods.
        int k = report.independentPeriods();
        if (k >= MIN_INDEPENDENT_PERIODS) {
            reasons.add(String.format("PASS sample size: %d independent periods (need %d).",
                    k, MIN_INDEPENDENT_PERIODS));
        } else {
            String b = String.format("FAIL sample size: %d independent periods against %d required. "
                            + "The row count is irrelevant here — those rows are a few hundred stocks "
                            + "moving together, re-measured every few days.",
                    k, MIN_INDEPENDENT_PERIODS);
            reasons.add(b);
            blockers.add(b);
        }

        // 2. Deflated significance on the paired difference.
        double alpha = BASE_ALPHA / Math.max(1, variantsTried);
        Double requiredT = Statistics.criticalT(Math.max(1, k - 1), alpha);
        Double observedT = best.tStatisticVsLive();
        if (observedT != null && requiredT != null && observedT >= requiredT) {
            reasons.add(String.format("PASS significance: paired t = %.2f clears the %.2f required "
                            + "at alpha %.4f (0.05 deflated by %d variants tried).",
                    observedT, requiredT, alpha, variantsTried));
        } else {
            String b = observedT == null
                    ? "FAIL significance: no paired t-statistic could be computed."
                    : String.format("FAIL significance: paired t = %.2f against %.2f required at "
                                    + "alpha %.4f (0.05 deflated by %d variants tried).",
                            observedT, requiredT == null ? Double.NaN : requiredT, alpha, variantsTried);
            reasons.add(b);
            blockers.add(b);
        }

        // 3. The effect must point the right way and be non-trivial.
        Double effect = best.meanIcVsLive();
        if (effect != null && effect > 0) {
            reasons.add(String.format("PASS direction: %s ranks better than live by %.4f mean rank "
                    + "correlation.", best.name(), effect));
        } else {
            String b = String.format("FAIL direction: %s does not out-rank the live vector "
                    + "(paired mean %.4f).", best.name(), effect == null ? Double.NaN : effect);
            reasons.add(b);
            blockers.add(b);
        }

        // 4. Stability across consecutive reviews.
        int consecutive = countConsecutive(priorReviews, best.name());
        // The current review counts as one, so one prior agreeing review satisfies a
        // requirement of two.
        int totalConsecutive = consecutive + 1;
        if (totalConsecutive >= REQUIRED_CONSECUTIVE_REVIEWS) {
            reasons.add(String.format("PASS stability: %s has led with a positive effect in %d "
                    + "consecutive reviews (need %d).", best.name(), totalConsecutive,
                    REQUIRED_CONSECUTIVE_REVIEWS));
        } else {
            String b = String.format("FAIL stability: %s has led with a positive effect in %d "
                            + "consecutive review(s), need %d. A single review is a result; the same "
                            + "result again next quarter is evidence.",
                    best.name(), totalConsecutive, REQUIRED_CONSECUTIVE_REVIEWS);
            reasons.add(b);
            blockers.add(b);
        }

        // 5. Comparable coverage — is it scoring the same universe?
        Double liveCoverage = null;
        for (WalkForwardReport.VariantResult v : report.variants()) {
            if (WeightVariant.LIVE.equals(v.name())) liveCoverage = v.meanCoverage();
        }
        if (liveCoverage != null && liveCoverage > 0 && best.meanCoverage() != null) {
            double ratio = best.meanCoverage() / liveCoverage;
            if (ratio >= MIN_RELATIVE_COVERAGE) {
                reasons.add(String.format("PASS coverage: %s scores %.0f%% of the cross-section "
                        + "the live vector scores.", best.name(), ratio * 100));
            } else {
                String b = String.format("FAIL coverage: %s scores only %.0f%% of the cross-section "
                                + "the live vector scores, so it is competing on an easier universe "
                                + "rather than beating it on the same one.", best.name(), ratio * 100);
                reasons.add(b);
                blockers.add(b);
            }
        } else {
            String b = "FAIL coverage: coverage could not be compared, so it is treated as unmet "
                    + "rather than assumed adequate.";
            reasons.add(b);
            blockers.add(b);
        }

        boolean eligible = blockers.isEmpty();
        String summary = eligible
                ? String.format("%s meets every condition. Adoption remains manual: this is a "
                        + "recommendation to change the weights, not a change.", best.name())
                : String.format("No weight change is justified. %d of 5 conditions unmet; the "
                        + "binding one is: %s", blockers.size(), blockers.get(0));

        return new Decision(eligible, best.name(), reasons, blockers, requiredT, observedT, summary);
    }

    /**
     * How many of the most recent reviews, without interruption, this variant led and passed
     * with a positive effect.
     *
     * <p>Stops at the first review that named a different variant, failed, or measured a
     * non-positive effect. A run broken and later resumed is not a run — that is the whole
     * meaning of "consecutive", and allowing gaps would let a variant accumulate credit across
     * quarters in which it lost.
     */
    static int countConsecutive(List<PriorReview> priorReviews, String variantName) {
        if (priorReviews == null || variantName == null) return 0;
        int n = 0;
        for (PriorReview r : priorReviews) {
            if (r == null || !variantName.equals(r.variantName()) || !r.passed()
                    || r.meanIcVsLive() == null || r.meanIcVsLive() <= 0) {
                break;
            }
            n++;
        }
        return n;
    }
}
