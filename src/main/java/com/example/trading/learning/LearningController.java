package com.example.trading.learning;

import com.example.trading.learning.validation.PromotionGate;
import com.example.trading.learning.validation.WalkForwardReport;
import com.example.trading.learning.validation.WeightReviewService;
import com.example.trading.persistence.ShadowCompositeEntity;
import com.example.trading.persistence.ShadowCompositeRepository;
import com.example.trading.persistence.WeightReviewEntity;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read and drive the learning substrate (SPEC §38).
 *
 * <p><b>Nothing here changes a score or a weight.</b> The two POST endpoints compute and record;
 * neither adopts anything. That is stated in the responses as well as here, because the single
 * most likely misreading of this feature is that a passing review has already taken effect.
 *
 * <p><b>Endpoint cost, which is the thing to get right in this codebase.</b> A GET can be
 * expensive here (Gotcha 17), so the split is deliberate and hand-verified:
 * <ul>
 *   <li>{@code GET /shadow}, {@code /reviews} — database only, safe on a page load.</li>
 *   <li>{@code POST /shadow/backfill} — database only but potentially thousands of writes.
 *       POST so no page load can reach it.</li>
 *   <li>{@code POST /review} — roughly one paced Kite call per symbol, about two minutes.
 *       Refused from 14:00 with a 409 carrying its reason, because the shared broker budget is
 *       what the afternoon scheduled jobs run on and exhausting it has starved them twice
 *       (B-014, B-049). The reason travels in the body rather than as a bare status, since
 *       {@code server.error.include-message} defaults to {@code never} and a guard whose
 *       explanation never reaches the caller is the silent failure it exists to prevent.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {

    /**
     * The window in which the scheduled jobs own the shared Kite budget: the 14:00 screening
     * through the 15:05-15:28 cluster that aborts silently at 15:30 if starved (B-014, B-049).
     *
     * <p>Bounded at both ends rather than open-ended after 14:00, which is what the other manual
     * scans do. Those guards were written when the application only ran inside market hours, so
     * "after 14:00" and "during the contention window" were the same thing; they are not the same
     * thing in an out-of-hours session, and an open-ended cutoff would refuse a review at 19:00
     * to protect jobs that finished four hours earlier. The reason for the guard is contention,
     * so the guard covers exactly the contention.
     */
    private static final LocalTime KITE_HEAVY_CUTOFF = LocalTime.of(14, 0);

    /** End of the scheduled-job cluster. After this nothing else is competing for the budget. */
    private static final LocalTime KITE_HEAVY_RESUME = LocalTime.of(15, 30);

    private final ShadowCompositeService shadowCompositeService;
    private final ShadowCompositeRepository shadowRepository;
    private final WeightVariantRegistry registry;
    private final WeightReviewService reviewService;
    private final MarketHoursService marketHoursService;

    /**
     * The pre-registered candidate set, with each variant's hypothesis and weights.
     *
     * <p>Worth being able to read without a database: the hypotheses are the part that must be
     * fixed before results are seen, and publishing them is what makes that claim checkable.
     */
    @GetMapping("/variants")
    public Map<String, Object> variants() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variantSetRevision", WeightVariantRegistry.VARIANT_SET_REVISION);
        out.put("alternativesTried", registry.count());
        out.put("automaticAdoptionEnabled", PromotionGate.AUTOMATIC_ADOPTION_ENABLED);
        out.put("note", "These steer nothing. They are recorded per screening run so that a "
                + "weighting question can eventually be answered with evidence rather than with "
                + "the current quarter's Information Coefficient panel.");
        out.put("variants", registry.all().stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", v.name());
            m.put("hypothesis", v.hypothesis());
            m.put("weights", v.weights());
            return m;
        }).toList());
        return out;
    }

    /**
     * Shadow composites for one screening date, or the most recent date that has any.
     *
     * <p>Follows Gotcha 20: "today" is empty until the 14:00 run, so an absent date walks back
     * to the latest date with rows rather than returning an empty list that reads as "the
     * variants found nothing".
     */
    @GetMapping("/shadow")
    public Map<String, Object> shadow(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String variant) {

        LocalDate resolved = date != null ? date : shadowRepository.findLatestDate().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", resolved);
        out.put("variantSetRevision", WeightVariantRegistry.VARIANT_SET_REVISION);

        if (resolved == null) {
            out.put("rows", List.of());
            out.put("note", "No shadow composites exist yet. POST /api/learning/shadow/backfill "
                    + "reconstructs them from existing screening history.");
            return out;
        }

        List<ShadowCompositeEntity> rows = variant != null && !variant.isBlank()
                ? shadowRepository.findByScreeningDateAndVariantName(resolved, variant)
                : shadowRepository.findByScreeningDate(resolved);
        out.put("rows", rows);
        out.put("count", rows.size());
        return out;
    }

    /**
     * Reconstruct shadow composites for every past screening date that has none.
     *
     * <p>Database-only: no Kite call, no NSE call. It can run at any hour without touching the
     * shared broker budget.
     *
     * @param force recompute dates that already have rows — needed after the variant set changes
     */
    @PostMapping("/shadow/backfill")
    public ShadowCompositeService.BackfillSummary backfill(
            @RequestParam(defaultValue = "false") boolean force) {
        return shadowCompositeService.backfill(force);
    }

    /**
     * Run a walk-forward evaluation and record the promotion gate's verdict.
     *
     * @param horizon forward window in days; 30 and 90 have matured outcomes today, 180 and 365
     *                do not and will honestly report an empty sample
     * @param embargo drop alternate blocks so no kept block's returns overlap the next. Leave on
     *                unless deliberately measuring how much the overlap was flattering the result
     * @param record  persist the verdict so it counts toward the gate's consecutive-review
     *                condition. Set false for an exploratory run — a review that was not meant
     *                to count must not be able to satisfy a stability requirement
     */
    @PostMapping("/review")
    public ResponseEntity<?> review(@RequestParam(defaultValue = "90") int horizon,
                                    @RequestParam(defaultValue = "true") boolean embargo,
                                    @RequestParam(defaultValue = "true") boolean record) {
        String refusal = kiteHeavyRefusal();
        if (refusal != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("refused", true, "reason", refusal));
        }

        WeightReviewService.ReviewResult result = reviewService.review(horizon, embargo, record);
        WalkForwardReport report = result.report();
        PromotionGate.Decision decision = result.decision();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adopted", false);
        out.put("automaticAdoptionEnabled", PromotionGate.AUTOMATIC_ADOPTION_ENABLED);
        out.put("eligible", decision.eligible());
        out.put("summary", decision.summary());
        out.put("challenger", decision.variantName());
        out.put("conditions", decision.reasons());
        out.put("blockers", decision.blockers());
        out.put("requiredT", decision.requiredT());
        out.put("observedT", decision.observedT());
        out.put("evaluation", report);
        out.put("reviewId", result.reviewId());
        out.put("note", "No weight was changed. Adoption is a person editing application.yml, "
                + "even when every condition is met.");
        return ResponseEntity.ok(out);
    }

    /** Recorded review verdicts, most recent first. Database only. */
    @GetMapping("/reviews")
    public List<WeightReviewEntity> reviews(@RequestParam(required = false) Integer horizon) {
        return horizon == null ? reviewService.history() : reviewService.history(horizon);
    }

    /**
     * Why a Kite-heavy manual run may not start now, or null when it may.
     *
     * <p>Mirrors the universe-scan guard: before 14:00 on a weekday there is room, and inside
     * the Saturday screening window there is room. Afterwards the daily screening and the close
     * ramp own the budget.
     */
    private String kiteHeavyRefusal() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        if (marketHoursService.isSaturdayScreeningWindow()) return null;
        LocalTime t = now.toLocalTime();
        boolean contended = !t.isBefore(KITE_HEAVY_CUTOFF) && t.isBefore(KITE_HEAVY_RESUME);
        if (!contended) return null;
        return String.format("Refused at %s IST. A walk-forward review costs roughly one paced "
                        + "Kite call per screened symbol (~2 minutes), and the same ~2.9 requests "
                        + "per second gate feeds the 14:00 screening and the 15:05-15:28 jobs, "
                        + "which abort silently at 15:30 if starved. Run it before %s IST, after "
                        + "%s IST, or in the Saturday screening window.",
                t.withNano(0), KITE_HEAVY_CUTOFF, KITE_HEAVY_RESUME);
    }
}
