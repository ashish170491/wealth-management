package com.example.trading.learning.validation;

import com.example.trading.learning.WeightVariant;
import com.example.trading.learning.WeightVariantRegistry;
import com.example.trading.persistence.ShadowCompositeEntity;
import com.example.trading.persistence.ShadowCompositeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Evaluates the pre-registered weight variants out of sample, block by block (SPEC §38.7,
 * step 2).
 *
 * <p><b>What it is designed to say today.</b> "There is no significant separation." That is the
 * correct answer with roughly five independent periods of data, and a harness that could not
 * produce it would be useless — the whole reason the previous machine-learning chain was deleted
 * is that nothing in it was capable of reporting its own insignificance. This class is built so
 * the negative result is the easy path and a positive one is hard to obtain by accident.
 *
 * <p><b>Blocks, not rows.</b> Dates are grouped into blocks one horizon wide. Within a block
 * each date yields one cross-sectional rank correlation between a variant's composite and the
 * forward return over the horizon; those are averaged into a single reading per block. A
 * variant's t-statistic is then computed over blocks, so the sample size is the number of
 * non-overlapping periods — around five — and not the eight-thousand-odd rows, which are a few
 * hundred stocks moving together and re-measured every few days (SPEC §25.5).
 *
 * <p><b>Embargo.</b> With the embargo on (the default), alternate blocks are dropped so that no
 * return window starting inside a kept block reaches into the next kept block. This halves an
 * already small sample, which is the correct trade: an overlapping sample does not contain more
 * information, it merely reports a smaller standard error for the same information.
 *
 * <p><b>Cross-sectional, and by rank.</b> Each date is correlated within itself, never pooled
 * across dates. Pooling lets one strong month dominate and is how a signal comes to look
 * predictive for having been measured mostly during a rally. Rank correlation rather than
 * Pearson because a composite is an ordering device and one stock that trebled should not carry
 * the panel.
 *
 * <p><b>Reads nothing but its own table and price history.</b> No score is changed, no
 * recommendation issued, no weight adjusted. The output is a report.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalkForwardHarness {

    /**
     * Below this many measurable stocks a date is skipped rather than correlated. A
     * cross-sectional correlation over a handful of names is dominated by which handful.
     */
    static final int MIN_STOCKS_PER_DATE = 20;

    /** Extra history fetched before the first screening date, so the first block is measurable. */
    private static final int WARM_SLACK_DAYS = 15;

    private final ShadowCompositeRepository shadowRepository;
    private final WeightVariantRegistry registry;
    private final DailyCandleCache candles;

    /**
     * Run the evaluation.
     *
     * <p>Costs one paced Kite call per distinct symbol on a cold cache — roughly 300 calls,
     * about two minutes against the shared ~2.9 requests-per-second gate. It must therefore not
     * be wired into a page load, and it is refused during the afternoon crunch by its caller
     * (B-049), not here: this class does the arithmetic and states its cost, the endpoint owns
     * the scheduling policy.
     *
     * @param horizonDays  the forward window, e.g. 30, 90, 180
     * @param embargo      drop alternate blocks so no kept block's returns overlap the next
     */
    public WalkForwardReport run(int horizonDays, boolean embargo) {
        int horizon = Math.max(1, horizonDays);

        List<ShadowCompositeEntity> all = shadowRepository.findFrom(LocalDate.of(2000, 1, 1));
        if (all.isEmpty()) {
            return empty(horizon, "No shadow composites exist yet. Run the back-fill first — "
                    + "without it the evidence clock starts today rather than in April 2026.");
        }

        // ---- organise: date -> variant -> rows -----------------------------------------
        Map<LocalDate, Map<String, List<ShadowCompositeEntity>>> byDate = new TreeMap<>();
        Set<String> symbols = new LinkedHashSet<>();
        Set<String> versions = new LinkedHashSet<>();
        int excludedInexact = 0;

        for (ShadowCompositeEntity row : all) {
            // Dropped from every variant alike, so each date's cross-section stays the same set
            // of stocks whichever variant is scoring it. Excluding per-variant would compare
            // rankings over different universes, which is not a comparison at all.
            if (!Boolean.TRUE.equals(row.getReconstructionExact())) {
                excludedInexact++;
                continue;
            }
            byDate.computeIfAbsent(row.getScreeningDate(), d -> new LinkedHashMap<>())
                    .computeIfAbsent(row.getVariantName(), v -> new ArrayList<>())
                    .add(row);
            symbols.add(row.getSymbol());
            if (row.getScoringVersion() != null) versions.add(row.getScoringVersion());
        }
        if (byDate.isEmpty()) {
            return empty(horizon, "Every shadow row was flagged as an inexact reconstruction. "
                    + "That is a defect in the back-fill, not a finding about the variants.");
        }

        LocalDate firstDate = ((TreeMap<LocalDate, ?>) byDate).firstKey();
        LocalDate lastDate = ((TreeMap<LocalDate, ?>) byDate).lastKey();
        LocalDate maturityCutoff = LocalDate.now().minusDays(horizon);

        // ---- price history --------------------------------------------------------------
        candles.warm(symbols, firstDate.minusDays(WARM_SLACK_DAYS));

        // ---- per-date cross-sectional IC, grouped into blocks ---------------------------
        List<String> variantNames = registry.all().stream().map(WeightVariant::name).toList();

        // block index -> variant -> the per-date ICs falling in that block
        Map<Integer, Map<String, List<Double>>> blocks = new TreeMap<>();
        Map<String, List<Double>> coverageByVariant = new LinkedHashMap<>();
        int datesEvaluated = 0, datesNotMatured = 0, datesTooSmall = 0;

        for (Map.Entry<LocalDate, Map<String, List<ShadowCompositeEntity>>> e : byDate.entrySet()) {
            LocalDate date = e.getKey();
            if (date.isAfter(maturityCutoff)) {
                datesNotMatured++;
                continue;
            }

            // Forward returns are resolved once per date and reused by every variant, so all
            // variants are scored against an identical outcome vector.
            Map<String, Double> forward = forwardReturns(e.getValue(), date, horizon);
            if (forward.size() < MIN_STOCKS_PER_DATE) {
                datesTooSmall++;
                continue;
            }

            int block = (int) (ChronoUnit.DAYS.between(firstDate, date) / horizon);
            boolean anyVariantScored = false;

            for (String variant : variantNames) {
                List<ShadowCompositeEntity> rows = e.getValue().get(variant);
                if (rows == null || rows.isEmpty()) continue;

                List<double[]> xy = new ArrayList<>();
                for (ShadowCompositeEntity row : rows) {
                    Double ret = forward.get(row.getSymbol());
                    if (ret == null) continue;
                    xy.add(new double[]{row.getComposite(), ret});
                }
                coverageByVariant.computeIfAbsent(variant, v -> new ArrayList<>())
                        .add(forward.isEmpty() ? 0.0 : 100.0 * xy.size() / forward.size());

                Statistics.Correlation ic = Statistics.spearman(xy);
                if (ic.present()) {
                    blocks.computeIfAbsent(block, b -> new LinkedHashMap<>())
                            .computeIfAbsent(variant, v -> new ArrayList<>())
                            .add(ic.value());
                    anyVariantScored = true;
                }
            }
            if (anyVariantScored) datesEvaluated++;
        }

        // ---- embargo: keep alternate blocks so return windows cannot overlap -------------
        List<Integer> ordered = new ArrayList<>(blocks.keySet());
        ordered.sort(Comparator.naturalOrder());
        List<Integer> kept = new ArrayList<>();
        if (embargo) {
            Integer lastKept = null;
            for (Integer b : ordered) {
                // A block is kept only if the previous kept block is at least two blocks back;
                // one full horizon then separates them, so no return measured inside a kept
                // block can still be running when the next kept block begins.
                if (lastKept == null || b - lastKept >= 2) {
                    kept.add(b);
                    lastKept = b;
                }
            }
        } else {
            kept.addAll(ordered);
        }

        // ---- aggregate per variant -------------------------------------------------------
        Map<String, List<Double>> blockIcByVariant = new LinkedHashMap<>();
        Map<String, List<Double>> pairedVsLive = new LinkedHashMap<>();

        for (Integer b : kept) {
            Map<String, List<Double>> perVariant = blocks.get(b);
            if (perVariant == null) continue;
            Double liveIc = Statistics.mean(perVariant.get(WeightVariant.LIVE));
            for (String variant : variantNames) {
                Double ic = Statistics.mean(perVariant.get(variant));
                if (ic == null) continue;
                blockIcByVariant.computeIfAbsent(variant, v -> new ArrayList<>()).add(ic);
                // Paired only when both were measurable in the same block; a difference against
                // a missing baseline is not a difference.
                if (liveIc != null && !WeightVariant.LIVE.equals(variant)) {
                    pairedVsLive.computeIfAbsent(variant, v -> new ArrayList<>()).add(ic - liveIc);
                }
            }
        }

        List<WalkForwardReport.VariantResult> results = new ArrayList<>();
        for (WeightVariant variant : registry.all()) {
            List<Double> ics = blockIcByVariant.getOrDefault(variant.name(), List.of());
            List<Double> paired = pairedVsLive.getOrDefault(variant.name(), List.of());
            results.add(new WalkForwardReport.VariantResult(
                    variant.name(),
                    variant.hypothesis(),
                    Statistics.mean(ics),
                    Statistics.stdDev(ics),
                    Statistics.tStatistic(ics),
                    WeightVariant.LIVE.equals(variant.name()) ? null : Statistics.mean(paired),
                    WeightVariant.LIVE.equals(variant.name()) ? null : Statistics.tStatistic(paired),
                    ics,
                    Statistics.mean(coverageByVariant.getOrDefault(variant.name(), List.of())),
                    ics.size()));
        }

        int independentPeriods = kept.size();
        String verdict = verdictFor(independentPeriods, versions.size());

        log.info("Walk-forward [{}d, embargo={}]: {} independent periods from {} blocks over "
                        + "{} dates ({} not yet matured, {} too small, {} rows inexact). "
                        + "Scoring versions in sample: {}. Verdict: {}",
                horizon, embargo, independentPeriods, ordered.size(), datesEvaluated,
                datesNotMatured, datesTooSmall, excludedInexact,
                versions.isEmpty() ? "none recorded" : versions, verdict);

        return new WalkForwardReport(independentPeriods, ordered.size(), embargo, horizon,
                firstDate, lastDate, datesEvaluated, datesNotMatured, datesTooSmall,
                excludedInexact, new ArrayList<>(versions), results, verdict);
    }

    /**
     * Forward return per symbol for one date, taken from daily closes at both ends.
     *
     * <p>Both legs come from the same candle series rather than the screening row's stored
     * price, so a difference between a quote taken during the day and a daily close cannot
     * manufacture a return. A symbol whose either leg is missing is absent from the map, and
     * therefore absent from every variant's cross-section on that date.
     */
    private Map<String, Double> forwardReturns(Map<String, List<ShadowCompositeEntity>> byVariant,
                                               LocalDate date, int horizon) {
        Set<String> symbols = new LinkedHashSet<>();
        byVariant.values().forEach(rows -> rows.forEach(r -> symbols.add(r.getSymbol())));

        Map<String, Double> out = new LinkedHashMap<>();
        LocalDate target = date.plusDays(horizon);
        for (String symbol : symbols) {
            Double ret = candles.returnPercent(symbol, date, target);
            if (ret != null) out.put(symbol, ret);
        }
        return out;
    }

    /**
     * The plain-English reading of the sample, written so that it cannot flatter the result.
     *
     * <p>It reports on the sample rather than on the winner, deliberately. Whether any variant
     * may actually be adopted is {@link PromotionGate}'s decision and needs more than a large
     * enough sample.
     */
    private String verdictFor(int independentPeriods, int versionCount) {
        StringBuilder s = new StringBuilder();
        if (independentPeriods < 2) {
            s.append("Not evaluable: fewer than two independent periods, so no spread and no "
                    + "t-statistic exist. Any apparent ranking here is one period of market noise.");
        } else if (independentPeriods < PromotionGate.MIN_INDEPENDENT_PERIODS) {
            s.append(String.format("Insufficient evidence: %d independent periods against the %d "
                            + "the promotion gate requires. Differences at this sample size are "
                            + "not distinguishable from chance, whatever their ordering suggests.",
                    independentPeriods, PromotionGate.MIN_INDEPENDENT_PERIODS));
        } else {
            s.append(String.format("Sample size adequate at %d independent periods. Significance "
                            + "is still the gate's decision, and it deflates for the %d variants tried.",
                    independentPeriods, registry.count()));
        }
        if (versionCount > 1) {
            s.append(String.format(" Note: %d different scoring versions are pooled in this sample; "
                            + "rows from different engines are not directly comparable (SPEC §38.1).",
                    versionCount));
        }
        return s.toString();
    }

    private WalkForwardReport empty(int horizon, String verdict) {
        log.info("Walk-forward [{}d]: {}", horizon, verdict);
        return new WalkForwardReport(0, 0, true, horizon, null, null, 0, 0, 0, 0,
                List.of(), List.of(), verdict);
    }
}
