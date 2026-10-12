package com.example.trading.portfolio.goal;

import com.example.trading.portfolio.AllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the investor is saving for, and what that implies (SPEC §53).
 *
 * <p><b>What this deliberately does not do.</b> It does not earmark holdings to goals, because this
 * app cannot tell which of your units belong to which goal and inventing that link would be worse
 * than leaving it blank. It does not project a portfolio value forward. It does not name a fund. It
 * produces no buy, sell or switch instruction. What it produces is the one decision that needs no
 * forecast - how much of a <em>dated</em> pot belongs in equity - together with what the app's own
 * archive says the worst stretch of that length actually did.
 *
 * <p><b>The comparison is between the blend your goals imply and the blend you hold.</b> Because
 * there is no earmarking, the blend is taken across all active goals weighted by target amount, and
 * compared to the whole combined portfolio. That is a real and useful reading and it is also a
 * limited one, which the response says in words rather than leaving the reader to infer.
 *
 * <p>DB-only and page-load safe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoalPlanService {

    /**
     * How far the held blend may sit from the implied one before it is called a gap, in points.
     *
     * <p>Ten. Below that the difference is inside what one month of market movement does to the
     * mix, so calling it a gap would raise a row that can never be cleared - which is the B-121
     * failure (ten drift warnings that fire daily and train the reader past the section).
     */
    public static final double GAP_TOLERANCE_PP = 10.0;

    private final InvestmentGoalRepository goalRepository;
    private final AllocationService allocationService;
    private final ObjectProvider<HorizonEvidenceSource> evidenceSource;

    public Map<String, Object> plan() {
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        List<InvestmentGoalEntity> goals = goalRepository.findByActiveTrueOrderByTargetDateAsc();

        out.put("asOf", today.toString());
        out.put("goalCount", goals.size());

        if (goals.isEmpty()) {
            out.put("stated", false);
            out.put("headline", "No goals are on record yet. A goal is a thing you are saving for "
                    + "with a date attached, and the date is what lets this app answer the one "
                    + "investment question that needs no forecast: how much of that money can sit "
                    + "somewhere it might be worth less on the day you need it.");
            out.put("goals", List.of());
            out.put("caveats", GoalMix.caveats());
            return out;
        }
        out.put("stated", true);

        AllocationService.CombinedWeights held = allocationService.computeCombinedWeights();
        HorizonEvidenceSource source = evidenceSource.getIfAvailable();

        // Every distinct horizon asked for in ONE read of the fund archive. Per-goal reads would
        // hit most of the NAV table once per goal on every page load, which is B-115's shape - a
        // cost that grows every week the archive deepens with nothing failing to mark the moment.
        Map<Integer, HorizonEvidenceSource.Evidence> evidence = source == null
                ? Map.of()
                : source.forHorizons(goals.stream()
                        .map(g -> GoalMath.monthsRemaining(today, g.getTargetDate()))
                        .distinct()
                        .map(mo -> new HorizonEvidenceSource.Request(
                                mo, GoalMix.forHorizon(mo).equityPct()))
                        .toList());

        List<Map<String, Object>> rows = new ArrayList<>();
        double weightedEquity = 0.0;
        double weightSum = 0.0;
        int undated = 0;
        BigDecimal totalTarget = BigDecimal.ZERO;
        BigDecimal totalEarmarked = BigDecimal.ZERO;
        boolean anyEarmark = false;

        for (InvestmentGoalEntity g : goals) {
            int months = GoalMath.monthsRemaining(today, g.getTargetDate());
            GoalMix.Mix mix = GoalMix.forHorizon(months);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", g.getId());
            r.put("name", g.getName());
            r.put("targetDate", g.getTargetDate().toString());
            r.put("monthsRemaining", months);
            r.put("horizonLabel", GoalMix.phrase(months));
            r.put("targetAmount", g.getTargetAmount());
            r.put("earmarkedValue", g.getEarmarkedValue());
            r.put("earmarkedAsOf", g.getEarmarkedAsOf() == null ? null
                    : g.getEarmarkedAsOf().toString());
            r.put("note", g.getNote());

            r.put("equityPct", mix.equityPct());
            r.put("debtPct", mix.debtPct());
            r.put("mixReason", mix.reason());

            BigDecimal shortfall = GoalMath.shortfall(g.getTargetAmount(), g.getEarmarkedValue());
            r.put("shortfall", shortfall);
            r.put("progressPct", GoalMath.progressPct(g.getTargetAmount(), g.getEarmarkedValue()));
            BigDecimal atZero = GoalMath.requiredMonthlyAtZeroReturn(shortfall, months);
            r.put("requiredMonthlyAtZeroReturn", atZero);
            r.put("requiredMonthlyMeasured", atZero != null);
            r.put("requiredMonthlyReason", requiredReason(g, months, shortfall));

            // What the archive says a stretch of exactly this length did. Per goal, because a
            // three-year goal and a ten-year goal are not protected against the same thing.
            HorizonEvidenceSource.Evidence ev = evidence.get(months);
            if (ev == null) {
                ev = HorizonEvidenceSource.Evidence.notMeasured(months,
                        "the fund archive is not available in this build, so there is nothing to "
                        + "check the split against. That is a gap in this app rather than a "
                        + "statement about how safe the split is.");
            }
            r.put("evidenceMeasured", ev.measured());
            r.put("worstEquityPct", ev.worstEquityPct());
            r.put("worstDebtPct", ev.worstDebtPct());
            r.put("medianEquityPct", ev.medianEquityPct());
            r.put("independentWindows", ev.independentWindows());
            r.put("evidenceFrom", ev.archiveFrom());
            r.put("evidenceTo", ev.archiveTo());
            r.put("evidenceReason", ev.reason());
            r.put("evidenceCaveat", ev.caveat());

            Double worstOnMix = ev.worstOnMixPct();
            r.put("worstOnMixPct", worstOnMix);
            r.put("worstOnMixNote", worstOnMix == null ? null : worstOnMixNote(mix, worstOnMix, ev));

            if (g.getTargetAmount() != null && g.getTargetAmount().signum() > 0) {
                double w = g.getTargetAmount().doubleValue();
                weightedEquity += mix.equityPct() * w;
                weightSum += w;
                totalTarget = totalTarget.add(g.getTargetAmount());
            } else {
                undated++;
            }
            if (g.getEarmarkedValue() != null) {
                totalEarmarked = totalEarmarked.add(g.getEarmarkedValue());
                anyEarmark = true;
            }
            rows.add(r);
        }

        out.put("goals", rows);
        out.put("goalsWithoutAnAmount", undated);
        out.put("totalTargetAmount", totalTarget.signum() == 0 ? null : totalTarget);
        out.put("totalEarmarked", anyEarmark ? totalEarmarked : null);

        // ------------------------------------------------------------------ implied vs held
        Map<String, Object> blend = new LinkedHashMap<>();
        if (weightSum > 0) {
            double impliedEquity = weightedEquity / weightSum;
            blend.put("measured", true);
            blend.put("impliedEquityPct", impliedEquity);
            blend.put("impliedDebtPct", 100.0 - impliedEquity);
            blend.put("weightedBy", "each goal's target amount");

            Held h = heldBlend(held);
            blend.put("heldEquityPct", h.equityPct());
            blend.put("heldDebtPct", h.debtPct());
            blend.put("heldOtherPct", h.otherPct());
            blend.put("heldTotalValue", h.total());
            blend.put("heldMeasured", h.total() > 0);
            if (h.total() > 0) {
                double gap = h.equityPct() - impliedEquity;
                blend.put("gapPp", gap);
                blend.put("gapExceedsTolerance", Math.abs(gap) > GAP_TOLERANCE_PP);
                blend.put("tolerancePp", GAP_TOLERANCE_PP);
                blend.put("verdict", gapSentence(gap, impliedEquity, h));
            } else {
                blend.put("gapPp", null);
                blend.put("gapExceedsTolerance", false);
                blend.put("verdict", "Nothing could be valued in the portfolio today, so there is "
                        + "nothing to compare the goals against.");
            }
        } else {
            // Every goal is dated but none carries an amount. The mixes are still right per goal;
            // what cannot be done is blending them, because there is nothing to weight by.
            blend.put("measured", false);
            blend.put("reason", "none of the goals has a target amount, so there is no weight to "
                    + "blend them by. Each goal's own split above still stands - what is missing is "
                    + "a single figure for the portfolio as a whole.");
        }
        out.put("blend", blend);

        out.put("headline", headline(rows, blend));
        out.put("caveats", planCaveats(anyEarmark));
        return out;
    }

    // ------------------------------------------------------------------ writes

    public InvestmentGoalEntity save(InvestmentGoalEntity goal) {
        goal.setActive(true);
        if (goal.getEarmarkedValue() != null && goal.getEarmarkedAsOf() == null) {
            // A stated figure with no date is a figure that cannot be seen to go stale.
            goal.setEarmarkedAsOf(LocalDate.now());
        }
        return goalRepository.save(goal);
    }

    /** Soft delete: the record is kept, the way every other retirement in this app works. */
    public boolean remove(long id) {
        return goalRepository.findById(id).map(g -> {
            g.setActive(false);
            goalRepository.save(g);
            return true;
        }).orElse(false);
    }

    // ------------------------------------------------------------------ helpers

    private record Held(double total, double equityPct, double debtPct, double otherPct) {}

    /**
     * The held blend, folded into the two buckets a goal plan is expressed in.
     *
     * <p>Direct shares and equity funds are one thing here - both are money whose value on a given
     * day is whatever the market says. Hybrid funds are deliberately <b>neither</b>: how much of one
     * is in equity moves with the manager and is not in this feed, so splitting it would be a guess
     * dressed as arithmetic. They are reported separately and named.
     */
    private static Held heldBlend(AllocationService.CombinedWeights w) {
        Map<String, Double> byClass = w.byAssetClass();
        double equity = byClass.getOrDefault("EQUITY_DIRECT", 0.0)
                + byClass.getOrDefault("EQUITY_FUND", 0.0);
        double debt = byClass.getOrDefault("DEBT_FUND", 0.0)
                + byClass.getOrDefault("CASH", 0.0);
        double other = 100.0 - equity - debt;
        return new Held(w.totalValue(), equity, debt, Math.max(0.0, other));
    }

    private static String requiredReason(InvestmentGoalEntity g, int months, BigDecimal shortfall) {
        if (g.getTargetAmount() == null) {
            return "No target amount is recorded for this goal, so there is nothing to work back "
                    + "from. The split above still holds - it depends only on the date.";
        }
        if (g.getEarmarkedValue() == null) {
            return "No amount is recorded as already set aside for this goal. That is left blank "
                    + "rather than treated as zero, because this app cannot tell which of your "
                    + "units belong to which goal and guessing would understate what you have.";
        }
        if (months <= 0) {
            return "The date has arrived, so what is left is not a monthly figure.";
        }
        if (shortfall != null && shortfall.signum() == 0) {
            return "Already fully funded on the figures recorded.";
        }
        return null;
    }

    /**
     * What the worst window means, said in words - <b>including when it is a gain</b>.
     *
     * <p>This archive begins in April 2018 and holds one crash that recovered inside a year, so for
     * any window longer than about two years the worst stretch on file still finishes ahead. A
     * column headed "worst stretch on record" showing +14% is the most flattering failure this page
     * could produce, so the positive case is named as a fact about the length of the record rather
     * than a fact about the risk (B-113's rule: look at the spread before believing the figure).
     */
    private static String worstOnMixNote(GoalMix.Mix mix, double worstOnMix,
                                         HorizonEvidenceSource.Evidence ev) {
        String split = Math.round(mix.equityPct()) + "/" + Math.round(mix.debtPct());
        String windows = ev.independentWindows() == null ? "the archive"
                : ev.independentWindows() + (ev.independentWindows() == 1
                        ? " independent stretch of this length on file"
                        : " independent stretches of this length on file");
        String opening = "Over the worst stretch of " + GoalMix.phrase(mix.months()) + " in this "
                + "app's fund archive, a pot split " + split + " between shares and debt would have "
                + "finished " + String.format("%.1f", Math.abs(worstOnMix)) + "% "
                + (worstOnMix < 0 ? "below" : "above") + " where it started. ";

        if (worstOnMix >= 0) {
            return opening + "That the worst one is still a gain says something about the record "
                    + "rather than about the split: it starts in April 2018 and contains one crash "
                    + "that recovered inside a year, so it has never held a long flat stretch. "
                    + "There " + (ev.independentWindows() != null && ev.independentWindows() == 1
                            ? "is " : "are ") + windows + ", and eight years is not long enough to "
                    + "have seen everything.";
        }
        return opening + "There " + (ev.independentWindows() != null && ev.independentWindows() == 1
                        ? "is " : "are ") + windows + ", so this describes what happened rather "
                + "than the range of what can happen - and it is a floor on how bad it got, "
                + "because a fund that failed outright left the record.";
    }

    private static String gapSentence(double gap, double implied, Held h) {
        String held = String.format("%.0f%%", h.equityPct());
        String want = String.format("%.0f%%", implied);
        if (Math.abs(gap) <= GAP_TOLERANCE_PP) {
            return "What you hold (" + held + " in shares and equity funds) is close to what your "
                    + "goals imply (" + want + "). Nothing here needs attention on that basis.";
        }
        if (gap > 0) {
            return "Your goals imply " + want + " in equity and you hold " + held + ". Money with a "
                    + "date attached is in something that can be worth less on the day it is "
                    + "needed. That is a statement about the dates you entered, not a view on the "
                    + "market - and the app does not say what to do about it.";
        }
        return "Your goals imply " + want + " in equity and you hold " + held + ". Over the longer "
                + "goals that means giving up the growth those dates would have allowed. Again a "
                + "statement about your dates, not a view on the market.";
    }

    private static String headline(List<Map<String, Object>> rows, Map<String, Object> blend) {
        long soon = rows.stream()
                .filter(r -> ((Number) r.get("monthsRemaining")).intValue() < GoalMix.NO_EQUITY_BELOW_MONTHS)
                .count();
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(rows.size() == 1 ? " goal is" : " goals are").append(" on record");
        if (soon > 0) {
            sb.append(", ").append(soon).append(soon == 1 ? " of which is" : " of which are")
                    .append(" inside three years and therefore gets no equity at all");
        }
        sb.append('.');
        Object verdict = blend.get("verdict");
        if (verdict instanceof String s && !s.isBlank()) {
            sb.append(' ').append(s);
        }
        return sb.toString();
    }

    private static List<String> planCaveats(boolean anyEarmark) {
        List<String> out = new ArrayList<>(GoalMix.caveats());
        out.add("This app does not know which of your holdings belong to which goal, and does not "
                + "try to work it out. The comparison above is between the blend your goals imply "
                + "and the blend of everything you own, which is a real reading and a limited one.");
        if (!anyEarmark) {
            out.add("No goal records an amount already set aside, so no shortfall or monthly figure "
                    + "is shown for any of them. Those fields are left blank rather than treated as "
                    + "zero.");
        }
        out.add("The monthly figure, where it appears, is what reaches the goal if the money earns "
                + "nothing at all. It is the only figure of its kind here that is not a forecast, "
                + "which is exactly why it is the one shown. Anything the money earns is a reason "
                + "the goal arrives early.");
        return out;
    }
}
