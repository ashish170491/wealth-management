package com.example.trading.portfolio.accumulation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulation planner (SPEC.md §8). Creates multi-tranche plans and tracks progress.
 * Tranche execution is out of scope for this MVP — investor fills tranches manually
 * via {@code POST /plan/{id}/tranche/{tid}/fill} after placing the trade with the broker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccumulationService {

    public static final String MODE_SIP = "SIP";
    public static final String MODE_PRICE_LADDER = "PRICE_LADDER";

    /**
     * Retained so legacy rows still read back, but <b>no new plan may use it</b> — see
     * {@link #validate} and SPEC §8.2 / §19 (B-077). A tranche fired by a signal is a buy
     * signal wearing a plan's clothes, and the triggers it named (BREAKOUT, SECTOR_REVERSAL,
     * market-direction confirmation) were deleted on 2026-09-03 (SPEC §39), so such a plan
     * never reminded either. Verified 2026-09-05: zero rows in the live database use it.
     */
    public static final String MODE_SIGNAL_GATED = "SIGNAL_GATED";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String TRANCHE_PENDING = "PENDING";
    public static final String TRANCHE_FILLED = "FILLED";
    public static final String TRANCHE_CANCELLED = "CANCELLED";

    private final AccumulationPlanRepository planRepository;
    private final AccumulationTrancheRepository trancheRepository;

    @Transactional
    public AccumulationDto.PlanView createPlan(AccumulationDto.CreatePlanRequest req) {
        validate(req);

        AccumulationPlanEntity plan = AccumulationPlanEntity.builder()
                .symbol(req.symbol())
                .targetAmount(req.targetAmount())
                .tranchesCount(req.tranchesCount())
                .mode(req.mode())
                .status(STATUS_ACTIVE)
                .startDate(req.startDate())
                .endDate(req.endDate())
                .notes(req.notes())
                .build();
        plan = planRepository.save(plan);

        double perTranche = req.targetAmount() / req.tranchesCount();
        for (int i = 0; i < req.tranchesCount(); i++) {
            AccumulationTrancheEntity t = AccumulationTrancheEntity.builder()
                    .planId(plan.getId())
                    .trancheNumber(i + 1)
                    .amount(perTranche)
                    .status(TRANCHE_PENDING)
                    .build();
            assignTrigger(t, plan, req, i);
            trancheRepository.save(t);
        }
        log.info("Accumulation: created plan {} for {} ({} tranches @ ₹{})",
                plan.getId(), plan.getSymbol(), plan.getTranchesCount(), perTranche);
        return toView(plan);
    }

    public List<AccumulationDto.PlanView> listPlans() {
        return planRepository.findAll().stream().map(this::toView).toList();
    }

    public AccumulationDto.PlanView getPlan(Long id) {
        AccumulationPlanEntity p = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + id));
        return toView(p);
    }

    @Transactional
    public AccumulationDto.PlanView cancelPlan(Long id) {
        AccumulationPlanEntity p = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + id));
        p.setStatus(STATUS_CANCELLED);
        planRepository.save(p);
        for (AccumulationTrancheEntity t : trancheRepository.findByPlanIdAndStatus(id, TRANCHE_PENDING)) {
            t.setStatus(TRANCHE_CANCELLED);
            trancheRepository.save(t);
        }
        return toView(p);
    }

    @Transactional
    public AccumulationDto.PlanView fillTranche(Long planId, Long trancheId, AccumulationDto.FillTrancheRequest req) {
        AccumulationTrancheEntity t = trancheRepository.findById(trancheId)
                .orElseThrow(() -> new IllegalArgumentException("Tranche not found: " + trancheId));
        if (!t.getPlanId().equals(planId)) {
            throw new IllegalArgumentException("Tranche " + trancheId + " does not belong to plan " + planId);
        }
        t.setFilledQuantity(req.quantity());
        t.setFilledPrice(req.price());
        t.setFilledDate(req.fillDate());
        t.setStatus(TRANCHE_FILLED);
        trancheRepository.save(t);

        AccumulationPlanEntity plan = planRepository.findById(planId).orElseThrow();
        // Auto-complete the plan when all tranches are filled or cancelled.
        List<AccumulationTrancheEntity> all = trancheRepository.findByPlanIdOrderByTrancheNumberAsc(planId);
        boolean anyOpen = all.stream().anyMatch(x -> TRANCHE_PENDING.equals(x.getStatus()));
        if (!anyOpen && STATUS_ACTIVE.equals(plan.getStatus())) {
            plan.setStatus(STATUS_COMPLETED);
            planRepository.save(plan);
        }
        return toView(plan);
    }

    private void validate(AccumulationDto.CreatePlanRequest req) {
        if (req.tranchesCount() <= 0) throw new IllegalArgumentException("tranchesCount must be > 0");
        if (req.targetAmount() <= 0) throw new IllegalArgumentException("targetAmount must be > 0");
        if (req.mode() == null) throw new IllegalArgumentException("mode is required");
        switch (req.mode()) {
            case MODE_SIP -> {
                if (req.startDate() == null || req.endDate() == null)
                    throw new IllegalArgumentException("SIP requires startDate and endDate");
                if (!req.endDate().isAfter(req.startDate()))
                    throw new IllegalArgumentException("endDate must be after startDate");
            }
            case MODE_PRICE_LADDER -> {
                if (req.initialPrice() == null || req.priceStepPercent() == null)
                    throw new IllegalArgumentException("PRICE_LADDER requires initialPrice and priceStepPercent");
            }
            case MODE_SIGNAL_GATED -> throw new UnsupportedModeException(
                    "SIGNAL_GATED accumulation is no longer supported. A tranche fired by a signal is a "
                    + "buy signal, which SPEC §19 rules out for this platform, and the signals it "
                    + "depended on (breakout scanner, sector reversal, market direction) were removed on "
                    + "2026-09-03. Plans in this mode never produced a reminder, so one would wait "
                    + "indefinitely for a tranche that cannot fire (B-077). Use " + MODE_SIP
                    + " for calendar tranches or " + MODE_PRICE_LADDER + " for price tranches "
                    + "(SPEC §12.12 supplies the levels).");
            default -> throw new IllegalArgumentException("Unknown mode: " + req.mode());
        }
    }

    private void assignTrigger(AccumulationTrancheEntity t, AccumulationPlanEntity plan,
                               AccumulationDto.CreatePlanRequest req, int index) {
        switch (plan.getMode()) {
            case MODE_SIP -> {
                long days = ChronoUnit.DAYS.between(req.startDate(), req.endDate());
                long offsetDays = (long) Math.round(days * index / (double) Math.max(1, req.tranchesCount() - 1));
                t.setTriggerDate(req.startDate().plusDays(offsetDays));
            }
            case MODE_PRICE_LADDER -> {
                double step = req.priceStepPercent() / 100.0;
                t.setTriggerPrice(req.initialPrice() * (1 - step * index));
            }
            // MODE_SIGNAL_GATED is refused in validate() and can never reach here (B-077).
            default -> { /* validated above */ }
        }
    }

    private AccumulationDto.PlanView toView(AccumulationPlanEntity plan) {
        List<AccumulationTrancheEntity> tranches = trancheRepository.findByPlanIdOrderByTrancheNumberAsc(plan.getId());
        List<AccumulationDto.TrancheView> trancheViews = new ArrayList<>(tranches.size());
        double filledAmount = 0;
        int filledQty = 0;
        double costSum = 0;
        for (AccumulationTrancheEntity t : tranches) {
            trancheViews.add(new AccumulationDto.TrancheView(
                    t.getId(), t.getTrancheNumber(), t.getAmount(),
                    t.getTriggerDate(), t.getTriggerPrice(), t.getTriggerSignal(),
                    t.getStatus(), t.getFilledQuantity(), t.getFilledPrice(), t.getFilledDate()));
            if (TRANCHE_FILLED.equals(t.getStatus()) && t.getFilledQuantity() != null && t.getFilledPrice() != null) {
                filledQty += t.getFilledQuantity();
                costSum += t.getFilledQuantity() * t.getFilledPrice();
                filledAmount += t.getFilledQuantity() * t.getFilledPrice();
            }
        }
        Double avgCost = filledQty > 0 ? costSum / filledQty : null;
        double progress = plan.getTargetAmount() > 0 ? (filledAmount / plan.getTargetAmount()) * 100.0 : 0.0;
        return new AccumulationDto.PlanView(
                plan.getId(), plan.getSymbol(), plan.getTargetAmount(), plan.getTranchesCount(),
                plan.getMode(), plan.getStatus(), plan.getStartDate(), plan.getEndDate(),
                filledAmount, filledQty, avgCost, progress, trancheViews);
    }

    /**
     * A plan mode the platform deliberately does not support. Carries its own reason because
     * {@code server.error.include-message} defaults to {@code never}, so a bare status would
     * tell the caller nothing (the B-049 pattern).
     */
    public static class UnsupportedModeException extends RuntimeException {
        public UnsupportedModeException(String message) {
            super(message);
        }
    }
}
