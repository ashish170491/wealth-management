package com.example.trading.portfolio.accumulation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Accumulation-planner endpoints (SPEC.md §8 and §16). */
@RestController
@RequestMapping("/api/portfolio/accumulate")
@RequiredArgsConstructor
public class AccumulationController {

    private final AccumulationService service;
    private final AccumulationReminderService reminderService;

    /** POST /api/portfolio/accumulate/reminder — manually trigger the tranche-due-today email. */
    @PostMapping("/reminder")
    public ResponseEntity<java.util.Map<String, Object>> triggerReminder() {
        try {
            int count = reminderService.sendIfAnyDue();
            return ResponseEntity.ok(java.util.Map.of(
                    "status", "SUCCESS",
                    "tranchesDueToday", count,
                    "emailSent", count > 0));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(java.util.Map.of(
                    "status", "FAILED", "error", e.getMessage()));
        }
    }

    /**
     * Create a plan. {@code SIGNAL_GATED} is refused with a 422 carrying its reason —
     * SPEC §8.2 / §19, B-077.
     */
    @PostMapping
    public ResponseEntity<AccumulationDto.PlanView> createPlan(@RequestBody AccumulationDto.CreatePlanRequest req) {
        return ResponseEntity.ok(service.createPlan(req));
    }

    @GetMapping
    public ResponseEntity<List<AccumulationDto.PlanView>> listPlans() {
        return ResponseEntity.ok(service.listPlans());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccumulationDto.PlanView> getPlan(@PathVariable Long id) {
        return ResponseEntity.ok(service.getPlan(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<AccumulationDto.PlanView> cancelPlan(@PathVariable Long id) {
        return ResponseEntity.ok(service.cancelPlan(id));
    }

    @PostMapping("/{planId}/tranche/{trancheId}/fill")
    public ResponseEntity<AccumulationDto.PlanView> fillTranche(
            @PathVariable Long planId, @PathVariable Long trancheId,
            @RequestBody AccumulationDto.FillTrancheRequest req) {
        return ResponseEntity.ok(service.fillTranche(planId, trancheId, req));
    }

    /**
     * 422 with the reason in the body. {@code server.error.include-message} defaults to
     * {@code never}, so a thrown status alone would reach the caller as a bare 422 — a
     * refusal whose explanation never arrives is the silent failure it exists to prevent
     * (B-049, mirrored from {@code WatchlistController} and {@code CoreHoldingController}).
     */
    /**
     * A malformed request is the caller's mistake, not a server fault. Without this it surfaced
     * as a bare 500, indistinguishable from a real failure — and indistinguishable from the
     * deliberate 422 above, which is the distinction that matters here: "we removed this on
     * purpose" and "you sent nonsense" are different answers.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", 400);
        body.put("error", "Rejected");
        body.put("reason", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AccumulationService.UnsupportedModeException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleUnsupportedMode(
            AccumulationService.UnsupportedModeException e) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", 422);
        body.put("error", "Rejected");
        body.put("reason", e.getMessage());
        return ResponseEntity.unprocessableEntity().body(body);
    }
}
