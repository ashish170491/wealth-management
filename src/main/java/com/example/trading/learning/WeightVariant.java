package com.example.trading.learning;

import com.example.trading.multibagger.MultibaggerScore;
import com.example.trading.persistence.MultibaggerScoreEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One alternative weighting of the seven multibagger dimensions (SPEC §38.7, step 1).
 *
 * <p><b>What this is for.</b> The system is asked to improve the accuracy of its own picks. It
 * cannot answer that by re-weighting on the current Information Coefficient panel — four months
 * of a risk-on quarter is not evidence, and doing so is the failure that ended the previous
 * machine-learning chain (Gotcha 27, SPEC §25.5). What it can do is record what a different
 * weighting <i>would</i> have scored, on every run, and let the evidence accumulate until there
 * is enough of it to judge. A variant computed here <b>steers nothing</b>: it is persisted
 * beside the live composite and read only by the walk-forward harness.
 *
 * <p><b>Pre-registered, not searched.</b> The candidate set is fixed in
 * {@link WeightVariantRegistry} with a written hypothesis per variant, settled before any
 * outcome is seen, and the number of candidates is carried into the promotion gate where it
 * deflates the significance threshold. A weighting chosen by trying vectors until one wins is a
 * multiple-testing machine whose winner's t-statistic means nothing.
 *
 * <p><b>Reconstruction, not re-screening.</b> A variant's composite is rebuilt from the
 * sub-scores a run already produced. This is exact rather than approximate because no bonus
 * depends on the dimension weights — the earnings, insider, analyst, wealth, capital-efficiency
 * and capex adjustments are computed from their own data and added afterwards, and the
 * HIGH_RISK cap is a hard floor applied last. So everything the engine did after weighting can
 * be recovered as a single constant and re-applied identically to every variant; see
 * {@link #compose(Integer, int)}. The reason this design was chosen over threading N composites
 * through the screener is that it also back-fills: the whole history since April 2026 can be
 * reconstructed from {@code multibagger_scores}, instead of the evidence clock starting today.
 *
 * <p>Pure — no Spring, no I/O, no repository. Deliberately mirrors
 * {@code MultibaggerScreenerService.weightedComposite}: <b>a null sub-score is dropped and the
 * remaining weight renormalised</b>, never replaced with a neutral 50. Replacing it would rank
 * a stock nobody could measure above one that was measured and found weak, which is the adverse
 * selection B-019 documents.
 *
 * @param name       stable identifier, persisted; changing it starts a new history
 * @param hypothesis what this vector claims, in one sentence, written before any result
 * @param weights    dimension to weight; absent or zero means the variant excludes it by choice
 */
public record WeightVariant(String name, String hypothesis, Map<Dimension, Double> weights) {

    /** The seven live dimensions, in the order the composite is documented (SPEC §12.5). */
    public enum Dimension {
        TECHNICAL_MOMENTUM,
        VOLUME_ACCUMULATION,
        RELATIVE_STRENGTH,
        PRICE_STRUCTURE,
        VALUATION,
        INSTITUTIONAL_INTEREST,
        FINANCIAL_QUALITY
    }

    /** Name reserved for the vector actually in force, so comparison is always like-for-like. */
    public static final String LIVE = "live";

    public WeightVariant {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "A weight variant must be named — the name is its persisted identity.");
        }
        weights = weights == null ? Map.of() : Map.copyOf(weights);
    }

    /**
     * Weight for one dimension; 0.0 when this variant excludes it.
     *
     * <p>A zero weight and an absent sub-score are different things and stay different: a zero
     * weight is a choice this variant made, an absent sub-score is a stock that could not be
     * measured. Only the second one renormalises.
     */
    public double weightOf(Dimension d) {
        Double w = weights.get(d);
        return w == null ? 0.0 : w;
    }

    /** Sum of declared weights. Not forced to 1.0 — renormalisation makes the scale irrelevant. */
    public double weightSum() {
        return weights.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /**
     * Weighted, renormalised score over the dimensions this variant uses and this stock could be
     * measured on — the variant's answer before any bonus.
     *
     * @return null when no weighted dimension was measurable. Null, never 0: a stock this
     *         variant cannot score is absent from its ranking, not bottom of it (Gotcha 21).
     *         {@code fundamentals-only} returns null for a stock with no fundamental coverage,
     *         and that gap is itself one of the things worth measuring.
     */
    public Integer weightedBase(Map<Dimension, Integer> subScores) {
        if (subScores == null || subScores.isEmpty()) return null;
        double weighted = 0;
        double available = 0;
        for (Map.Entry<Dimension, Integer> e : subScores.entrySet()) {
            Integer score = e.getValue();
            if (score == null) continue;              // unmeasured: drop and renormalise
            double w = weightOf(e.getKey());
            if (w <= 0) continue;                     // excluded by this variant, by choice
            weighted += score * w;
            available += w;
        }
        if (available <= 0) return null;
        return (int) Math.round(weighted / available);
    }

    /** How many of this variant's weighted dimensions were actually measured on this stock. */
    public int dimensionsMeasured(Map<Dimension, Integer> subScores) {
        if (subScores == null) return 0;
        int n = 0;
        for (Map.Entry<Dimension, Integer> e : subScores.entrySet()) {
            if (e.getValue() != null && weightOf(e.getKey()) > 0) n++;
        }
        return n;
    }

    /**
     * Apply the run's post-weighting adjustment to a variant's base and clamp to 0-100.
     *
     * <p>{@code adjustment} is everything the live engine did after weighting — the market-cap
     * adjustment, all eleven bonuses, and any hard cap — recovered by subtracting the live
     * variant's base from the stored composite. Held constant across variants deliberately:
     * none of it depends on the weights, so varying it would be measuring something other than
     * the weighting.
     *
     * @return null when {@code weightedBase} was null; the variant does not rank this stock
     */
    public static Integer compose(Integer weightedBase, int adjustment) {
        if (weightedBase == null) return null;
        return Math.max(0, Math.min(100, weightedBase + adjustment));
    }

    /**
     * The seven sub-scores from a freshly computed score object.
     *
     * <p>The first four are primitives on {@link MultibaggerScore} and are therefore never null;
     * the last three are wrappers and genuinely may be. Boxing the primitives here keeps one
     * code path instead of two.
     */
    public static Map<Dimension, Integer> subScores(MultibaggerScore s) {
        if (s == null) return Map.of();
        Map<Dimension, Integer> m = new LinkedHashMap<>();
        m.put(Dimension.TECHNICAL_MOMENTUM, s.getTechnicalMomentumScore());
        m.put(Dimension.VOLUME_ACCUMULATION, s.getVolumeAccumulationScore());
        m.put(Dimension.RELATIVE_STRENGTH, s.getRelativeStrengthScore());
        m.put(Dimension.PRICE_STRUCTURE, s.getPriceStructureScore());
        m.put(Dimension.VALUATION, s.getValuationScore());
        m.put(Dimension.INSTITUTIONAL_INTEREST, s.getInstitutionalInterestScore());
        m.put(Dimension.FINANCIAL_QUALITY, s.getFinancialQualityScore());
        return m;
    }

    /**
     * The same seven sub-scores from a persisted row — the back-fill path.
     *
     * <p><b>Known limit, stated rather than hidden.</b> The first four columns are primitive
     * {@code int}, so a row written before one of those dimensions existed reads as {@code 0},
     * not as unmeasured. That is a property of the table, not of this method, and it is why the
     * harness partitions by {@code scoringVersion} instead of pooling all history: rows from
     * different engines are not comparable, whatever the column says.
     */
    public static Map<Dimension, Integer> subScores(MultibaggerScoreEntity e) {
        if (e == null) return Map.of();
        Map<Dimension, Integer> m = new LinkedHashMap<>();
        m.put(Dimension.TECHNICAL_MOMENTUM, e.getTechnicalMomentumScore());
        m.put(Dimension.VOLUME_ACCUMULATION, e.getVolumeAccumulationScore());
        m.put(Dimension.RELATIVE_STRENGTH, e.getRelativeStrengthScore());
        m.put(Dimension.PRICE_STRUCTURE, e.getPriceStructureScore());
        m.put(Dimension.VALUATION, e.getValuationScore());
        m.put(Dimension.INSTITUTIONAL_INTEREST, e.getInstitutionalInterestScore());
        m.put(Dimension.FINANCIAL_QUALITY, e.getFinancialQualityScore());
        return m;
    }
}
