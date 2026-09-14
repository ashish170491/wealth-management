package com.example.trading.learning;

import com.example.trading.multibagger.MultibaggerConfig;
import com.example.trading.persistence.RecommendationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the current {@link ScoringVersion} for each scoring engine (SPEC §38.1).
 *
 * <p>One place, so the version stamped on a {@code multibagger_scores} row and the version
 * stamped on the {@code recommendations} row produced from that same score can never
 * disagree — they are the same call.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScoringVersionRegistry {

    private final MultibaggerConfig multibaggerConfig;

    /**
     * Version of the multibagger composite as currently configured.
     *
     * <p>Covers everything that can move a composite from configuration: the eight weights
     * (B-019 — the sum is validated at boot, but a <i>redistribution</i> summing to 1.0 is
     * still a different engine) and every shadow-mode flag with its bonus magnitudes
     * (Gotcha 30/42 — flipping one of those to true is the single most consequential
     * scoring change this system can make).
     *
     * <p>Never throws: a provenance stamp must not be able to fail a screening run.
     */
    public String forMultibagger() {
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("w.technicalMomentum", multibaggerConfig.getTechnicalMomentumWeight());
            params.put("w.volumeAccumulation", multibaggerConfig.getVolumeAccumulationWeight());
            params.put("w.relativeStrength", multibaggerConfig.getRelativeStrengthWeight());
            params.put("w.priceStructure", multibaggerConfig.getPriceStructureWeight());
            params.put("w.valuation", multibaggerConfig.getValuationWeight());
            params.put("w.institutionalInterest", multibaggerConfig.getInstitutionalInterestWeight());
            params.put("w.financialQuality", multibaggerConfig.getFinancialQualityWeight());
            params.put("insiderPulse.actionable", multibaggerConfig.isInsiderPulseActionable());
            params.put("insiderPulse.strong", multibaggerConfig.getInsiderPulseStrongBonus());
            params.put("insiderPulse.moderate", multibaggerConfig.getInsiderPulseModerateBonus());
            params.put("capex.actionable", multibaggerConfig.isCapexActionable());
            params.put("capex.expansion", multibaggerConfig.getCapexExpansionBonus());
            params.put("capex.investing", multibaggerConfig.getCapexInvestingBonus());
            params.put("capex.harvesting", multibaggerConfig.getCapexHarvestingPenalty());
            params.put("turnaround.actionable", multibaggerConfig.isTurnaroundActionable());
            params.put("turnaround.bonus", multibaggerConfig.getTurnaroundBonus());
            params.put("forensic.actionable", multibaggerConfig.isForensicActionable());
            return ScoringVersion.of("mb", ScoringVersion.MULTIBAGGER_CODE_REVISION, params);
        } catch (Exception e) {
            log.warn("Could not resolve multibagger scoring version ({}). Rows will be stamped "
                    + "'{}', which excludes them from any version-aware comparison rather than "
                    + "silently pooling them with the current engine.", e.getMessage(), ScoringVersion.UNKNOWN);
            return ScoringVersion.UNKNOWN;
        }
    }

    /**
     * Version for a recommendation source.
     *
     * <p>QUANT_DISCOVERY and SECTOR_REVERSAL keep their scoring constants in code rather
     * than in a configuration bean, so their versions track only the hand-maintained code
     * revision in {@link ScoringVersion}. Bump the constant when their scoring changes —
     * there is nothing here that can detect it automatically.
     */
    private String macroVersion() {
        try {
            java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("map", com.example.trading.macro.MacroExposureMap.version());
            return ScoringVersion.of("mx", ScoringVersion.MACRO_EVENT_CODE_REVISION, params);
        } catch (Exception e) {
            log.warn("Macro exposure version could not be resolved ({}); readings are stamped '{}' "
                    + "so they are excluded from version-aware comparison rather than pooled with "
                    + "a map they were not produced by.", e.getMessage(), ScoringVersion.UNKNOWN);
            return ScoringVersion.UNKNOWN;
        }
    }

    public String forSource(RecommendationEntity.Source source) {
        if (source == null) return ScoringVersion.UNKNOWN;
        return switch (source) {
            case MULTIBAGGER -> forMultibagger();
            case QUANT_DISCOVERY -> ScoringVersion.of("qd", ScoringVersion.QUANT_DISCOVERY_CODE_REVISION, null);
            case SECTOR_REVERSAL -> ScoringVersion.of("sr", ScoringVersion.SECTOR_REVERSAL_CODE_REVISION, null);
            // Macro exposure's version carries the CONTENT of the rule file, not just a code
            // revision: the map is the engine here, so re-wording a rule changes what a reading
            // means and the reading must be filed under a different version.
            case MACRO_EVENT -> macroVersion();
        };
    }
}
