package com.example.trading.valuation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.trading.fundamentals.AdjustedShareSeries;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * One stock's valuation-gap reading: the response of {@code GET /api/valuation-gap} and the single
 * source the validation snapshot is mapped from (SPEC §57). It is built once, by
 * {@code ValuationGapService.assemble}, and both the screen and the snapshot read it, so the two
 * cannot disagree about the same inputs (Gotcha 85).
 *
 * <p><b>What is absent is absent.</b> Every field is {@code @JsonInclude(NON_NULL)}: a figure that
 * could not be measured is missing from the payload, never {@code 0} and never a stub (SPEC §21
 * rule 7). On a refusal every derived number is absent; only the raw record that explains the
 * refusal remains.
 *
 * <p><b>What this deliberately does not carry:</b> a rupee range, a price for any future date, a
 * forward price-to-earnings, an upside, a score, a rank or a verdict word of any kind. The
 * vocabulary is pinned by {@code ValuationVocabularyTest}, field names and enum constants included.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder(toBuilder = true)
public record ValuationReading(
        String symbol,
        String resolvedFrom,
        String ruleset,
        String fingerprint,
        Status status,
        Reason reasonCode,
        String reason,
        LocalDate valuationDate,
        Double price,
        String priceNote,
        Horizon horizon,
        Basis basis,
        Earnings earnings,
        TrendComparison trendComparison,
        RequiredGrowthGrid.Grid requiredGrowthGrid,
        Section125 section125Reading,
        Map<String, Object> blocks,
        List<AppliedRule> appliedRules,
        String differenceBetweenMethods,
        Snapshots snapshots,
        String coverageLine,
        List<String> caveats,
        /* What the snapshot needs. Never serialised: the screen shows the reading, not the plumbing. */
        @JsonIgnore Capture capture) {

    /** How much of the reading could be produced. */
    public enum Status { MEASURED, PARTIAL, UNAVAILABLE }

    /**
     * Why a reading is refused (the first eleven gates, in order) or why one block is withheld
     * ({@code TREND_UNRELIABLE}, {@code NO_SECTION_125}). A closed set: every code is printed with
     * the failing number, and every code has a label in {@code format.js}.
     */
    public enum Reason {
        NEVER_SCREENED, UNSUPPORTED_SECTOR, UNCLASSIFIED, STALE_PRICE, NO_PRICE_ON_FILE,
        INSUFFICIENT_HISTORY, BASIS_UNKNOWN, STALE_LATEST_YEAR, BASIS_SWITCH, FIELD_MISSING,
        SHARE_COUNT_INCONSISTENT, UNEXPLAINED_SHARE_STEP, AMBIGUOUS_SHARE_STEP, SHARE_BASIS_MISMATCH,
        LOSS_IN_RECORD, EPS_CROSSCHECK_FAILED, EPS_UNVERIFIABLE, HORIZON_TOO_SHORT,
        TREND_UNRELIABLE, NO_SECTION_125
    }

    /** Which cohort a stored fit belongs to when graded later. A label, not a precondition. */
    public enum TrendCohort { GATES_PASSED, GATES_WITHHELD, NOT_FITTED }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Horizon(String label, LocalDate endsOn, Double yearsFromValuationDate,
                          Integer growthYears, Integer baseFiscalYear) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Basis(Boolean consolidated, Integer observations, Integer firstFy, Integer lastFy,
                        Integer growthIntervals) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SeriesRow(int fy, Double netProfitCr, Double shareCountCr, Double faceValue, Double shareFactor,
                            Double epsAdjusted, LocalDate availableFrom, Boolean availableFromIsInferred) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CorporateAction(int fy, double ratio, String via) {
        static CorporateAction of(AdjustedShareSeries.Action a) {
            return new CorporateAction(a.fiscalYear(), a.ratio(), a.via());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CrossCheckView(Double quarterlyEpsSum, Double ratio, Double tolerance, Boolean passed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Gates(String stdErr, String rSquared) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trend(Double growthPct, Double stdErrPp, Integer observations, Integer firstFy, Integer lastFy,
                        Double rSquared, Double endpointCagrPct, Gates gates, Boolean reliable) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Normalisation(String ruleId, List<String> flags) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Earnings(List<SeriesRow> series, List<CorporateAction> corporateActions, Double normalisedEps,
                           Normalisation normalisation, CrossCheckView crossCheck, Trend trend, String provenance) {
    }

    /**
     * Block D. {@code epsIfTrendContinues} is present only when both reliability gates pass, is
     * computed on read, and is never stored (SPEC §57.2, decision 2).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrendComparison(String label, String statement, Double pe0, Double epsIfTrendContinues,
                                  Integer referenceYear, String withheldReason) {
    }

    /** What a methodology card says about itself, so the two cards can be read side by side. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Methodology(String name, List<String> terms) {
    }

    /**
     * Card F: the existing reverse-DCF reading, exactly as stored and never recomputed. There is no
     * verdict field here by design: the full §12.5 reading lives in the "Is the price sensible?"
     * panel, and repeating it here is how two engines end up answering one question in one
     * vocabulary (Gotcha 85).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Section125(Methodology methodology, Double impliedGrowthPct, String note, String pointer) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppliedRule(String id, String why) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshots(long count, LocalDateTime latestCreatedAt) {
    }

    /**
     * Everything the validation snapshot stores, taken from the same computation as the screen.
     * Raw and adjusted series are kept even on a refusal: a stock that later turns loss-making
     * must still have its earlier years on record. No future earnings level appears here and none
     * can be derived without the stored slope and intercept (rule VG-V1).
     */
    public record Capture(
            LocalDate snapshotDate, String symbolAnswered, String scoringVersion,
            Double price, Double marketCapCr, Double pe0,
            Integer baseFiscalYear, Integer firstFiscalYear, Integer observations, Boolean consolidated,
            Double latestEps, Double normalisedEps, String normalisationFlags,
            Double trendLnIntercept, Double trendLnSlope, Double trendGrowthPct, Double trendStdErrPp,
            Double trendRSquared, TrendCohort cohort, Double endpointCagrPct,
            Double shareCountBaseCr, Double faceValueBase, Double dcfImpliedGrowthPct,
            Double crosscheckRatio,
            List<EpsSeries.Year> series, List<AdjustedShareSeries.Action> actions) {
    }

    /** The closed wire vocabulary, for the vocabulary and surface tests. */
    public static List<String> vocabulary() {
        List<String> out = new java.util.ArrayList<>();
        for (Status s : Status.values()) out.add(s.name());
        for (Reason r : Reason.values()) out.add(r.name());
        for (TrendCohort c : TrendCohort.values()) out.add(c.name());
        return List.copyOf(out);
    }
}
