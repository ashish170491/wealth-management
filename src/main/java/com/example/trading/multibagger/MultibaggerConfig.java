package com.example.trading.multibagger;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for Multibagger Screening & Scoring Module.
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "trading.multibagger")
public class MultibaggerConfig {

    private boolean enabled = true;
    private boolean emailEnabled = true;

    // Scoring weights (must sum to 1.0). Seven dimensions since 2026-09-03: Sector Tailwind
    // was removed and its 0.08 redistributed in proportion to what the others already held,
    // so the removal does not double as a re-weighting on IC (which the sample cannot support
    // -- CLAUDE.md gotcha 27). 0.18/0.92 -> 0.20, 0.12/0.92 -> 0.13, and so on.
    private double technicalMomentumWeight = 0.20;
    private double volumeAccumulationWeight = 0.13;
    private double relativeStrengthWeight = 0.13;
    private double priceStructureWeight = 0.13;
    private double valuationWeight = 0.14;
    private double institutionalInterestWeight = 0.11;
    private double financialQualityWeight = 0.16;

    // Thresholds
    /**
     * Top slice of each screening run eligible to be a candidate, as a percentage.
     *
     * <p>Paired with {@link #minScoreForCandidate}: a stock must be in the top N% of the
     * universe AND clear the absolute floor. The percentile keeps the shortlist a constant,
     * reviewable size and is immune to score-scale drift (B-018/B-019 each shifted every
     * score uniformly and sailed through an absolute-only gate); the floor prevents
     * promoting the best of a uniformly bad market.
     *
     * <p>20% of a ~290-stock universe is ~58 names — still broad for a long-term portfolio,
     * but a fifth of the 201 the absolute-only gate was emitting.
     */
    private double candidateTopPercentile = 20.0;

    private int minScoreForCandidate = 60;        // Absolute floor, applied with the percentile

    /**
     * Composite score at or above which a pick is treated as **high conviction**.
     *
     * <p>Set from measured forward returns, not intuition. Over 2026-04-20 → 2026-08-25, holding
     * the screened universe constant, median 4-month return by score band was:
     *
     * <pre>
     *   80+     +8.43%     70-79   +7.23%     65-69   +4.71%
     *   50-64   +2.34%     &lt;50     -0.19%
     * </pre>
     *
     * <p>The ladder is monotone in the median, and the step up happens at **70**: the 70-79 band
     * returned a median 7.23% with 63.0% of names positive, against 4.71% / 57.7% for the 65-69
     * band that also clears the recommendation bar. Concentrating attention there is the
     * evidence-backed version of "own fewer, better things".
     *
     * <p><b>Deliberately a presentation tier, not a new filter.</b> It reorders what the reports
     * lead with; it does not discard 65-69 picks or change any score. The supporting sample is a
     * single 4-month window (n=46 in the 70-79 band), which is enough to justify ranking but far
     * too little to justify throwing candidates away — see SPEC §25.5.
     */
    private int highConvictionScore = 70;
    private int minScoreForStrongCandidate = 75;   // Strong multibagger candidate
    private int topCandidatesInReport = 20;        // Max candidates in email report

    /**
     * Under-discovery score at or above which a candidate is surfaced in the report's
     * "Under the radar" section (SPEC §12.10). A presentation threshold only — the
     * under-discovery score never enters the composite.
     */
    private int underRadarMinScore = 60;

    /**
     * Whether the Insider Pulse signal (SPEC §28) may adjust the composite.
     *
     * <p><b>Defaults to false — shadow mode.</b> The signal is computed, persisted and
     * measured by the per-dimension IC machinery, but contributes zero points until it has
     * earned the right to move a recommendation.
     *
     * <p>This is the gate the plan's "bonus first, dimension later" principle was missing.
     * That principle gates <i>promotion to a weighted dimension</i> on measured IC, but a
     * bonus changes picks the day it ships — so a new signal would re-rank the portfolio for
     * a quarter before anyone could tell whether it works. Given that the engine's own edge
     * is +1.54pp/month at t≈1.24 (p≈0.28) over ~5 independent periods, adding unvalidated
     * free parameters to the score is how a risk-on quarter gets fitted as skill.
     *
     * <p>Flip to true only after SPEC §25.5's gate is met: IC computed over >=100 outcomes
     * for the INSIDER_PULSE dimension, stable across two consecutive weekly runs.
     */
    private boolean insiderPulseActionable = false;

    /** Points awarded when {@link #insiderPulseActionable} is on. Kept modest by design. */
    private int insiderPulseStrongBonus = 8;
    private int insiderPulseModerateBonus = 4;

    /**
     * Whether the Capex-Cycle signal (SPEC §31) may adjust the composite.
     *
     * <p><b>Defaults to false — shadow mode</b>, for the same reason as
     * {@link #insiderPulseActionable}. Capex is the most tempting signal yet added, because
     * it genuinely leads the P&amp;L rather than following it — which is exactly why it
     * should not be trusted before it is measured. A leading indicator that leads in the
     * wrong direction is worse than a lagging one, and "companies building capacity go up"
     * is a hypothesis with a large literature on both sides.
     *
     * <p>The verdict is computed, persisted to {@code multibagger_scores.capex_score} and
     * measured by per-dimension IC throughout. Flip to true only when SPEC §25.5's gate is met.
     */
    private boolean capexActionable = false;

    /** Points awarded when {@link #capexActionable} is on. */
    private int capexExpansionBonus = 8;
    private int capexInvestingBonus = 4;
    private int capexHarvestingPenalty = 3;

    /**
     * Whether the Turnaround signal (SPEC §32.3) may adjust the composite.
     *
     * <p><b>Defaults to false — shadow mode.</b> Turnarounds are the category most prone to
     * hindsight: the ones that worked are famous, the ones that kept failing are forgotten,
     * and the criteria here were chosen by reading about the former. Measure first.
     */
    private boolean turnaroundActionable = false;

    /** Points awarded when {@link #turnaroundActionable} is on. */
    private int turnaroundBonus = 6;

    /**
     * Whether forensic red flags (SPEC §32.4) may reduce the composite.
     *
     * <p><b>Defaults to TRUE — deliberately unlike the signals above.</b> Those are claims
     * that a stock will go up, and an unvalidated one steers the portfolio on a guess. These
     * are risk controls, and the asymmetry is the point: being wrong about a bonus costs a
     * missed opportunity, being wrong about serial dilution or an auditor resignation costs
     * capital. The system already ships its other risk control — the HIGH_RISK composite cap
     * — armed by default, for the same reason. A guard that is switched off protects nothing.
     *
     * <p>Set false only to isolate the flags' effect while diagnosing a scoring change.
     */
    private boolean forensicActionable = true;

    /**
     * Whether macro and geopolitical exposure (SPEC §48) may adjust the composite.
     *
     * <p><b>Defaults to false — shadow mode</b>, and this one is not a close call. The signal is
     * new, the exposure map behind it is hand-written, and the two features this app deleted on
     * 2026-09-03 were deleted for turning news into a directional call nobody ever measured
     * (SPEC §39.3). Every reading is filed as a MACRO_EVENT row and judged at 180 and 365 days;
     * until that reads positive it is worth exactly nothing, which is what this flag enforces.
     *
     * <p>There is deliberately no companion bonus figure. Adding one before the measurement
     * exists would be choosing the size of an effect that has not been shown to have a sign.
     */
    private boolean macroExposureActionable = false;

    /**
     * Whether an analyst price target may move a composite (SPEC §49.9). <b>False, permanently in
     * this phase.</b>
     *
     * <p>An analyst target is a third party's opinion arriving through a news headline — the exact
     * input shape of the two engines removed on 2026-09-03, neither of which had ever produced a
     * measured hit rate (SPEC §39.3). The ledger exists to produce that evidence: every recorded
     * target is measured against the Nifty 50 over its own horizon, and a per-house record is
     * published with its own sample floor. Until that record says something, this is worth zero.
     *
     * <p>As with macro exposure there is deliberately <b>no companion bonus figure</b>. Choosing
     * how many points a brokerage upgrade is worth, before the measurement has established that
     * it has a sign, is exactly the fitting-a-quarter-as-skill failure Gotcha 30 exists to
     * prevent. Pinned by {@code NewSignalShadowModeTest}.
     */
    private boolean analystTargetActionable = false;

    /**
     * Fail fast if the eight scoring weights don't sum to 1.0 (B-019).
     *
     * <p>These are {@code @ConfigurationProperties}, so application.yml overrides them
     * individually — a dimension omitted from the yml silently keeps its Java default.
     * That is how the weights came to sum to 1.15 for months: the yml listed the seven
     * pre-2026-04 dimensions (summing to 1.00) and never added financial-quality-weight.
     * Every composite was inflated 15%, so a "60" threshold really meant 52 and ~70% of
     * the universe passed it.
     *
     * <p>Refusing to boot is deliberate. A silently mis-scaled score is worse than a dead
     * app: it produces plausible-looking recommendations that are systematically wrong,
     * and the accuracy loop can't detect a uniform scale factor.
     */
    @PostConstruct
    void validateWeights() {
        double sum = technicalMomentumWeight + volumeAccumulationWeight + relativeStrengthWeight
                + priceStructureWeight + valuationWeight + institutionalInterestWeight
                + financialQualityWeight;
        if (Math.abs(sum - 1.0) > 0.001) {
            throw new IllegalStateException(String.format(
                    "Multibagger scoring weights must sum to 1.0 but sum to %.3f. "
                    + "Every composite would be scaled by %.1f%%, invalidating the score thresholds. "
                    + "Check that ALL SEVEN trading.multibagger.*-weight keys are set in application.yml "
                    + "(technical-momentum, volume-accumulation, relative-strength, price-structure, "
                    + "valuation, institutional-interest, financial-quality).",
                    sum, (sum - 1.0) * 100));
        }
        log.info("Multibagger scoring weights validated: sum = {} across 7 dimensions", String.format("%.3f", sum));
    }

    // Technical analysis parameters
    private int weeklyEmaShort = 12;              // Weekly EMA short period
    private int weeklyEmaLong = 26;               // Weekly EMA long period
    private int monthlyRsiPeriod = 14;            // Monthly RSI period
    private double minRsiForUptrend = 50.0;       // RSI above this = uptrend
    private double maxRsiForEntry = 75.0;         // RSI above this = overbought (less room to grow)

    // Volume accumulation
    private int volumeLookbackWeeks = 12;         // Weeks to look for volume accumulation pattern
    private double volumeSurgeMultiplier = 1.5;   // Volume > 1.5x avg = accumulation

    // Relative strength
    private double minRelativeStrength = 1.0;     // Stock outperforming index (RS > 1.0)

    // Price structure
    private double nearHighPercent = 10.0;        // Within 10% of 52-week high = strong
    private double baseBuildingMinWeeks = 8;       // Minimum weeks of consolidation for base pattern

    // Valuation
    private double undervaluedPeThreshold = -15.0; // PE deviation < -15% = undervalued (bonus)
    private double overvaluedPePenalty = 30.0;      // PE deviation > 30% = penalty

    // Market cap categories (in crores)
    private double smallCapMax = 10000.0;         // < 10,000 Cr = small cap (highest potential)
    private double midCapMax = 50000.0;           // < 50,000 Cr = mid cap
    // > 50,000 Cr = large cap (lower multibagger potential)

    // Screening universe
    private int maxStocksToScreen = 1000;         // Maximum stocks to screen per run (raised for full-universe tier)
    private int historyDays = 365;                // Days of price history to analyze

    /**
     * Which market-cap tiers to scan. See
     * {@link com.example.trading.scanner.Nifty200WatchlistService} tier constants —
     * {@code LARGE_ONLY} | {@code LARGE_MID} | {@code LARGE_MID_SMALL} | {@code ALL}.
     */
    private String screeningTier = "LARGE_MID_SMALL";

    // Tier-specific min composite scores to keep weekly emails high-signal (SPEC §12.5).
    private int smallcapMinScore = 55;            // Stricter than default 45 to reduce noise
    private int microcapMinScore = 60;            // Even stricter for micro-caps

    // Extra quality filters applied to small/micro tier only.
    private boolean requireSmallcapQualityFilters = true;
    private double smallcapMinPrice = 20.0;       // Avoid penny stocks below ₹20
    private double smallcapMaxPledgePercent = 30.0; // Reject if promoter pledge exceeds 30%
    private boolean smallcapRequirePositiveEarnings = true; // Reject DECLINING earnings verdict
}
