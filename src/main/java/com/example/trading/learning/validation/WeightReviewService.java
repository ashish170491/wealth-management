package com.example.trading.learning.validation;

import com.example.trading.learning.WeightVariantRegistry;
import com.example.trading.persistence.WeightReviewEntity;
import com.example.trading.persistence.WeightReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one weight review end to end: evaluate, judge, record (SPEC §38.7).
 *
 * <p>The recording is the part that is easy to skip and expensive to have skipped. The gate's
 * stability condition asks whether the same challenger led two consecutive reviews, which is
 * answerable only from a written record — recomputing the previous verdict from today's data
 * would produce today's answer twice and call it agreement.
 *
 * <p><b>Nothing here changes a weight.</b> The output is a verdict and a stored row. Adoption is
 * a human editing {@code application.yml}, deliberately, because a scoring engine that quietly
 * becomes something nobody chose cannot have its history pooled with its own past (SPEC §38.1).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeightReviewService {

    /**
     * Prior reviews consulted for the stability check. Small on purpose: the condition is about
     * consecutive recent agreement, and a longer window would let an old run be resumed after a
     * quarter in which the variant lost.
     */
    private static final int PRIOR_REVIEW_LOOKBACK = 8;

    private final WalkForwardHarness harness;
    private final WeightVariantRegistry registry;
    private final WeightReviewRepository reviewRepository;

    /** A review's full output: what was measured, what was decided, and the row that recorded it. */
    public record ReviewResult(WalkForwardReport report, PromotionGate.Decision decision, Long reviewId) {
    }

    /**
     * Evaluate at one horizon and record the verdict.
     *
     * <p>Costs roughly one paced Kite call per distinct symbol on a cold cache — about two
     * minutes. The caller enforces when it may run; this method does the work.
     *
     * @param horizonDays forward window, e.g. 30, 90 or 180
     * @param embargo     drop alternate blocks so kept blocks' return windows cannot overlap
     * @param record      persist the verdict. False for an exploratory run — a review that was
     *                    not meant to count must not be able to satisfy a stability condition
     */
    public ReviewResult review(int horizonDays, boolean embargo, boolean record) {
        WalkForwardReport report = harness.run(horizonDays, embargo);

        List<PromotionGate.PriorReview> priors = loadPriors(horizonDays);
        PromotionGate.Decision decision = PromotionGate.evaluate(report, registry.count(), priors);

        Long id = null;
        if (record) {
            id = persist(horizonDays, report, decision);
        }

        log.info("Weight review [{}d]: {} | challenger={} periods={} t={} required={}",
                horizonDays, decision.eligible() ? "ELIGIBLE" : "REFUSED",
                decision.variantName(), report.independentPeriods(),
                decision.observedT(), decision.requiredT());
        if (!decision.blockers().isEmpty()) {
            log.info("Weight review [{}d]: binding objection — {}", horizonDays, decision.blockers().get(0));
        }
        return new ReviewResult(report, decision, id);
    }

    /** Recorded reviews at one horizon, most recent first. DB-only. */
    public List<WeightReviewEntity> history(int horizonDays) {
        return reviewRepository.findRecent(horizonDays);
    }

    /** Every recorded review, most recent first. DB-only. */
    public List<WeightReviewEntity> history() {
        return reviewRepository.findAllRecent();
    }

    private List<PromotionGate.PriorReview> loadPriors(int horizonDays) {
        List<WeightReviewEntity> rows = reviewRepository.findRecent(horizonDays);
        List<PromotionGate.PriorReview> out = new ArrayList<>();
        for (WeightReviewEntity r : rows) {
            if (out.size() >= PRIOR_REVIEW_LOOKBACK) break;
            // A review recorded under a different candidate-set revision is not comparable: the
            // same variant name may have meant a different vector. Treated as a break in the run
            // rather than skipped, because skipping would silently join two runs across the
            // change that ought to have reset them.
            if (r.getVariantSetRevision() != null
                    && r.getVariantSetRevision() != WeightVariantRegistry.VARIANT_SET_REVISION) {
                break;
            }
            out.add(new PromotionGate.PriorReview(
                    r.getVariantName(),
                    Boolean.TRUE.equals(r.getPassedExceptStability()),
                    r.getMeanIcVsLive()));
        }
        return out;
    }

    private Long persist(int horizonDays, WalkForwardReport report, PromotionGate.Decision decision) {
        try {
            // "Passed except stability" is what the next review reads. Computed by removing the
            // stability objection from the blocker list rather than by re-running the gate with
            // the condition disabled, so the two can never drift apart.
            boolean passedExceptStability = decision.blockers().stream()
                    .noneMatch(b -> b.startsWith("FAIL stability"));

            Double effect = null;
            for (WalkForwardReport.VariantResult v : report.variants()) {
                if (v.name().equals(decision.variantName())) effect = v.meanIcVsLive();
            }

            WeightReviewEntity row = reviewRepository.save(WeightReviewEntity.builder()
                    .reviewDate(LocalDate.now())
                    .horizonDays(horizonDays)
                    .variantName(decision.variantName())
                    .variantSetRevision(WeightVariantRegistry.VARIANT_SET_REVISION)
                    .variantsTried(registry.count())
                    .independentPeriods(report.independentPeriods())
                    .meanIcVsLive(effect)
                    .pairedTStatistic(decision.observedT())
                    .requiredT(decision.requiredT())
                    .passedExceptStability(passedExceptStability)
                    .eligible(decision.eligible())
                    .adopted(false)
                    .summary(truncate(decision.summary(), 2000))
                    .reasons(truncate(String.join("\n", decision.reasons()), 4000))
                    .build());
            return row.getId();
        } catch (Exception e) {
            log.warn("Weight review could not be recorded ({}). The verdict stands for this run but "
                    + "will not count toward the gate's consecutive-review condition, so the next "
                    + "review will see a shorter run than actually occurred.", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
