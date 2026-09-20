package com.example.trading.portfolio.goal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Goals (SPEC §53). Nothing here contributes a point to any score, and there is no field to flip.
 *
 * <p>The two GETs are DB-only and page-load safe. The writes are the investor's own, which is the
 * §46.7 click-only carve-out - a goal is by definition something only the person saving for it can
 * state, and there is deliberately no seeder (the whole of B-121 was a seeded default read as a
 * statement).
 */
@RestController
@RequestMapping("/api/portfolio/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalPlanService planService;
    private final InvestmentGoalRepository repository;

    /** Every active goal with its implied split, the evidence behind it, and the blend. DB-only. */
    @GetMapping
    public Map<String, Object> plan() {
        return planService.plan();
    }

    /**
     * The rule table itself, so the rules can be read rather than trusted.
     *
     * <p>No I/O at all. Same pattern as {@code GET /api/macro/map} and
     * {@code GET /api/learning/variants}: a screen that applies a judgement must be able to show it.
     */
    @GetMapping("/rules")
    public Map<String, Object> rules() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> bands = new java.util.ArrayList<>();
        for (int months : new int[]{6, 24, 36, 60, 84, 120, 180}) {
            GoalMix.Mix mix = GoalMix.forHorizon(months);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("months", months);
            row.put("horizonLabel", GoalMix.phrase(months));
            row.put("equityPct", mix.equityPct());
            row.put("debtPct", mix.debtPct());
            row.put("reason", GoalMix.bandReason(months));
            bands.add(row);
        }
        out.put("bands", bands);
        out.put("maxEquityPct", GoalMix.MAX_EQUITY_PCT);
        out.put("noEquityBelowMonths", GoalMix.NO_EQUITY_BELOW_MONTHS);
        out.put("statedNotMeasured", true);
        out.put("note", "These percentages are a stated rule, not a measurement. They are printed "
                + "here so you can disagree with them. What is measured is the figure shown beside "
                + "each goal: the worst stretch of exactly that length in this app's own fund "
                + "archive, and what it would have done to the split proposed.");
        // Deliberately NOT carrying GoalMix.caveats() here. GET /api/portfolio/goals already
        // sends them, the page renders them once in its own section, and printing the same four
        // sentences twice on one screen is what teaches a reader to skip both (B-132's pass).
        return out;
    }

    /** Create or update a goal. The investor's own write - there is no other writer. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(@RequestBody GoalRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return bad("a goal needs a name");
        }
        if (request.targetDate() == null) {
            return bad("a goal needs a date. Without one there is nothing this app can say about "
                    + "it: the date is the only thing that decides how much of the money can sit "
                    + "somewhere it might be worth less when you need it.");
        }
        InvestmentGoalEntity e = request.id() == null ? null
                : repository.findById(request.id()).orElse(null);
        if (e == null) {
            e = InvestmentGoalEntity.builder().build();
        }
        e.setName(request.name().trim());
        e.setTargetDate(request.targetDate());
        e.setTargetAmount(request.targetAmount());
        e.setEarmarkedValue(request.earmarkedValue());
        if (request.earmarkedValue() != null) {
            e.setEarmarkedAsOf(request.earmarkedAsOf() == null ? LocalDate.now()
                    : request.earmarkedAsOf());
        }
        e.setNote(request.note());
        InvestmentGoalEntity saved = planService.save(e);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("name", saved.getName());
        out.put("targetDate", saved.getTargetDate().toString());
        return ResponseEntity.ok(out);
    }

    /** Retire a goal. Soft delete, so the record survives. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable long id) {
        if (!planService.remove(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("removed", true, "id", id));
    }

    private static ResponseEntity<Map<String, Object>> bad(String reason) {
        // The reason travels in the body: server.error.include-message is `never`, so a thrown
        // status arrives bare and a refusal nobody can read is the silent failure it exists to
        // prevent (B-049).
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("error", "invalid goal", "reason", reason));
    }

    /**
     * @param earmarkedValue what the investor says is already set aside, in rupees; null means
     *                       not stated and is never read as zero
     */
    public record GoalRequest(
            Long id,
            String name,
            LocalDate targetDate,
            BigDecimal targetAmount,
            BigDecimal earmarkedValue,
            LocalDate earmarkedAsOf,
            String note) {}
}
