package com.example.trading.learning;

import com.example.trading.learning.WeightVariant.Dimension;
import com.example.trading.multibagger.MultibaggerConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed, pre-registered set of alternative weightings evaluated in shadow (SPEC §38.7).
 *
 * <p><b>Why the set is fixed in code and small.</b> The tempting version of "make the app
 * self-learning" is a search: generate weight vectors, score them against past returns, adopt
 * the winner. With ~5 independent periods of data that procedure is guaranteed to produce a
 * winner and guaranteed that the winner means nothing — the best of many vectors on a short
 * sample beats the incumbent by construction. The defence is not a better search. It is
 * declaring the candidates in advance, in a numbered list, with the hypothesis each one tests
 * written down before any outcome is known, and then charging the promotion gate for every one
 * of them ({@code PromotionGate} deflates its significance threshold by {@link #count()}).
 *
 * <p><b>Adding a variant is not free.</b> Every addition raises the bar the eventual winner
 * must clear, for every variant, permanently — including the ones already accumulating
 * evidence. Add one only with a hypothesis that is not a rotation of an existing one, and bump
 * {@link #VARIANT_SET_REVISION} so it is visible that the set changed.
 *
 * <p><b>None of these steer anything.</b> {@link WeightVariant#LIVE} is the vector actually in
 * force, read from {@link MultibaggerConfig} so it is always exactly what the engine used
 * rather than a copy that can drift. The rest exist only to be measured.
 */
@Component
@RequiredArgsConstructor
public class WeightVariantRegistry {

    /**
     * Bump when the candidate set changes — a variant added, removed, or its weights altered.
     * Persisted on every shadow row so a later analysis can tell whether two rows named
     * {@code quality-tilt} meant the same vector. Same discipline as
     * {@link ScoringVersion#MULTIBAGGER_CODE_REVISION}, and for the same reason: two different
     * things filed under one name cannot be separated afterwards.
     */
    public static final int VARIANT_SET_REVISION = 1;

    private final MultibaggerConfig config;

    /**
     * The live vector, read from configuration at call time.
     *
     * <p>Not a hardcoded copy: the weights are {@code @ConfigurationProperties} and the whole
     * point of the exercise is comparing alternatives against what actually ran. A stale copy
     * here would compare every alternative against a vector that stopped being live months ago
     * — which is B-019's failure shape (a configured value and a Java default disagreeing,
     * silently) reproduced inside the tool built to catch it.
     */
    public WeightVariant live() {
        Map<Dimension, Double> w = new LinkedHashMap<>();
        w.put(Dimension.TECHNICAL_MOMENTUM, config.getTechnicalMomentumWeight());
        w.put(Dimension.VOLUME_ACCUMULATION, config.getVolumeAccumulationWeight());
        w.put(Dimension.RELATIVE_STRENGTH, config.getRelativeStrengthWeight());
        w.put(Dimension.PRICE_STRUCTURE, config.getPriceStructureWeight());
        w.put(Dimension.VALUATION, config.getValuationWeight());
        w.put(Dimension.INSTITUTIONAL_INTEREST, config.getInstitutionalInterestWeight());
        w.put(Dimension.FINANCIAL_QUALITY, config.getFinancialQualityWeight());
        return new WeightVariant(WeightVariant.LIVE,
                "The weighting actually in force. Present so every comparison is against what ran, "
                        + "not against a remembered copy of it.", w);
    }

    /**
     * Live vector first, then the pre-registered alternatives.
     *
     * <p>Order is stable and is the order they were registered in; it is not a ranking.
     */
    public List<WeightVariant> all() {
        List<WeightVariant> out = new ArrayList<>();
        out.add(live());
        out.addAll(ALTERNATIVES);
        return out;
    }

    /** Alternatives only — the live vector excluded. This is the number the gate deflates by. */
    public List<WeightVariant> alternatives() {
        return ALTERNATIVES;
    }

    /**
     * How many distinct hypotheses are being tried against the same data.
     *
     * <p>Counts alternatives, not the live vector: the live vector is the incumbent being
     * tested against, not a contender, so including it would inflate the correction and make
     * the gate harder to pass for no reason.
     */
    public int count() {
        return ALTERNATIVES.size();
    }

    private static Map<Dimension, Double> vec(double tech, double vol, double rs, double structure,
                                              double valuation, double institutional, double quality) {
        Map<Dimension, Double> w = new LinkedHashMap<>();
        w.put(Dimension.TECHNICAL_MOMENTUM, tech);
        w.put(Dimension.VOLUME_ACCUMULATION, vol);
        w.put(Dimension.RELATIVE_STRENGTH, rs);
        w.put(Dimension.PRICE_STRUCTURE, structure);
        w.put(Dimension.VALUATION, valuation);
        w.put(Dimension.INSTITUTIONAL_INTEREST, institutional);
        w.put(Dimension.FINANCIAL_QUALITY, quality);
        return w;
    }

    /**
     * Five alternatives, each testing something the system does not currently know.
     *
     * <p>They are deliberately far apart rather than small perturbations of the live vector.
     * Small perturbations are unmeasurable at this sample size — they would produce rankings
     * nearly identical to live and differences indistinguishable from noise — so they would
     * cost significance-threshold deflation while carrying no information. The hypotheses below
     * can actually be resolved by data, and two of them (fundamentals-only, price-only)
     * partition the dimension set, which makes their results directly interpretable.
     */
    private static final List<WeightVariant> ALTERNATIVES = List.of(

            new WeightVariant("equal",
                    "Weighting adds nothing over not weighting: an equal split across all seven "
                            + "dimensions ranks as well as the tuned vector. This is the null "
                            + "hypothesis the live weights have never been tested against.",
                    vec(1, 1, 1, 1, 1, 1, 1)),

            new WeightVariant("quality-tilt",
                    "Over a one-to-three-year hold, balance-sheet quality and valuation should "
                            + "dominate, and price behaviour should matter less than the 59% the "
                            + "live vector allocates to it.",
                    vec(0.08, 0.05, 0.05, 0.05, 0.27, 0.15, 0.35)),

            new WeightVariant("momentum-tilt",
                    "The opposite claim, stated so that it can lose: price and volume behaviour "
                            + "carry the signal, and the fundamental dimensions mostly add noise "
                            + "and coverage gaps.",
                    vec(0.34, 0.20, 0.20, 0.16, 0.04, 0.03, 0.03)),

            new WeightVariant("fundamentals-only",
                    "Price-derived dimensions contribute nothing at a multi-year horizon. Scores "
                            + "on valuation, institutional interest and financial quality alone — "
                            + "and is therefore also the variant that is uncomputable for a stock "
                            + "with no fundamental coverage, which is itself worth measuring.",
                    vec(0, 0, 0, 0, 0.35, 0.25, 0.40)),

            new WeightVariant("price-only",
                    "The complement of fundamentals-only, and the one variant computable for "
                            + "every stock in the universe: if it wins, the engine's fundamental "
                            + "work is not earning its coverage cost.",
                    vec(0.35, 0.22, 0.22, 0.21, 0, 0, 0))
    );
}
