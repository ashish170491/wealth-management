package com.example.trading.portfolio.core;

import com.example.trading.portfolio.core.CoreDto.CoreClassification;
import com.example.trading.portfolio.core.CoreDto.CoreTier;
import com.example.trading.portfolio.core.CoreDto.GateStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seven gates and how a tier is resolved from them (SPEC §35.2).
 *
 * <p>Change a gate and something here should fail. If it does not, the rule was never pinned.
 */
class CoreHoldingGatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    private final CoreHoldingConfig config = new CoreHoldingConfig();
    private final CoreHoldingService service =
            new CoreHoldingService(config, new DurabilityScorer(config));

    private CoreClassification classify(CoreEvidenceFixture f) {
        return service.classify(f.build(), TODAY);
    }

    private GateStatus statusOf(CoreClassification c, String code) {
        return c.gateStatuses().get(code);
    }

    // ------------------------------------------------------------------ tier resolution

    @Test
    @DisplayName("A stock passing every measurable gate with no soft signal is CORE")
    void allGatesPassIsCore() {
        CoreClassification c = classify(new CoreEvidenceFixture());
        assertThat(c.provisionalTier()).isEqualTo(CoreTier.CORE);
        assertThat(c.evidenceGateCount()).isGreaterThanOrEqualTo(config.getMinMeasuredGates());
        assertThat(c.softSignals()).isEmpty();
    }

    @Test
    @DisplayName("One soft signal turns CORE into CORE_WATCH, never into SATELLITE")
    void softSignalIsWatchNotDemotion() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.decayVerdict = "WATCH";
        CoreClassification c = classify(f);
        assertThat(c.provisionalTier()).isEqualTo(CoreTier.CORE_WATCH);
        assertThat(c.softSignals()).isNotEmpty();
        // G5 still passes: WATCH is "keep an eye on it", not "the thesis broke".
        assertThat(statusOf(c, "G5")).isEqualTo(GateStatus.PASS);
    }

    @Test
    @DisplayName("Any failed gate is SATELLITE, whatever the other six say")
    void oneFailureIsSatellite() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.financialQualityVerdict = "WEAK";
        CoreClassification c = classify(f);
        assertThat(c.provisionalTier()).isEqualTo(CoreTier.SATELLITE);
        assertThat(c.reasons()).anyMatch(r -> r.startsWith("Financial quality"));
    }

    /**
     * R-4, the finding this whole distinction exists for. Three gates pass on absent data; without
     * the {@code PASS_NO_DATA} rule they would count as evidence and this stock would reach CORE on
     * two real gates plus three blanks.
     */
    @Test
    @DisplayName("R-4: a gate that passes on absent data does not count toward the quorum")
    void noDataPassesDoNotReachTheQuorum() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.forensicMeasured = Boolean.FALSE;      // G4 -> PASS_NO_DATA
        f.forensicFlagCount = null;
        f.insiderPulseVerdict = null;            // G6 -> PASS_NO_DATA
        f.convictionRecordExists = Boolean.FALSE; // G7 -> PASS_NO_DATA
        f.decayVerdict = "NO_DATA";              // G5 -> UNMEASURED

        CoreClassification c = classify(f);

        assertThat(statusOf(c, "G4")).isEqualTo(GateStatus.PASS_NO_DATA);
        assertThat(statusOf(c, "G6")).isEqualTo(GateStatus.PASS_NO_DATA);
        assertThat(statusOf(c, "G7")).isEqualTo(GateStatus.PASS_NO_DATA);
        assertThat(c.evidenceGateCount()).isEqualTo(3);   // G1, G2, G3 only
        assertThat(c.provisionalTier())
                .as("three free passes must not add up to a CORE verdict")
                .isEqualTo(CoreTier.UNCLASSIFIED);
        assertThat(c.missingInputs()).isNotEmpty();
    }

    @Test
    @DisplayName("Exactly five gates carrying evidence is enough; four is not")
    void quorumBoundary() {
        CoreEvidenceFixture five = new CoreEvidenceFixture();
        five.forensicMeasured = Boolean.FALSE;       // PASS_NO_DATA
        five.forensicFlagCount = null;
        five.insiderPulseVerdict = null;             // PASS_NO_DATA
        // G1, G2, G3, G5, G7 carry evidence = 5
        assertThat(classify(five).evidenceGateCount()).isEqualTo(5);
        assertThat(classify(five).provisionalTier()).isEqualTo(CoreTier.CORE);

        CoreEvidenceFixture four = new CoreEvidenceFixture();
        four.forensicMeasured = Boolean.FALSE;
        four.forensicFlagCount = null;
        four.insiderPulseVerdict = null;
        four.earningsConsistencyScore = null;        // G3 -> UNMEASURED
        assertThat(classify(four).evidenceGateCount()).isEqualTo(4);
        assertThat(classify(four).provisionalTier()).isEqualTo(CoreTier.UNCLASSIFIED);
    }

    @Test
    @DisplayName("UNCLASSIFIED names what was missing and never reads as a verdict")
    void unclassifiedExplainsItself() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.capitalEfficiencyVerdict = null;
        f.financialQualityVerdict = null;
        f.earningsConsistencyScore = null;
        CoreClassification c = classify(f);

        assertThat(c.provisionalTier()).isEqualTo(CoreTier.UNCLASSIFIED);
        assertThat(c.reasons()).anyMatch(r -> r.contains("data gap, not a verdict"));
        assertThat(c.missingInputs()).isNotEmpty();
    }

    // ------------------------------------------------------------------ individual gates

    @Test
    @DisplayName("G1: financials are judged on ROE and ROA, not on a ROCE verdict")
    void g1FinancialPath() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.capitalEfficiencyVerdict = "NA_FINANCIAL";
        f.roePercent = 14.1;
        f.roaPercent = 1.59;
        assertThat(service.g1CapitalEfficiency(f.build()).status()).isEqualTo(GateStatus.PASS);

        f.roaPercent = 0.7;   // a bank earning under 0.8% on assets is not a compounder
        assertThat(service.g1CapitalEfficiency(f.build()).status()).isEqualTo(GateStatus.FAIL);

        f.roePercent = null;
        assertThat(service.g1CapitalEfficiency(f.build()).status()).isEqualTo(GateStatus.UNMEASURED);
    }

    @Test
    @DisplayName("G4: a fired forensic flag fails the gate; no history is PASS_NO_DATA, not clean")
    void g4ForensicStates() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        assertThat(service.g4Forensic(f.build()).status()).isEqualTo(GateStatus.PASS);

        f.forensicFlagCount = 1;
        f.forensicSummary = "RECEIVABLES:HIGH";
        assertThat(service.g4Forensic(f.build()).status()).isEqualTo(GateStatus.FAIL);

        f.forensicMeasured = Boolean.FALSE;
        f.forensicFlagCount = null;
        CoreDto.Gate g = service.g4Forensic(f.build());
        assertThat(g.status()).isEqualTo(GateStatus.PASS_NO_DATA);
        assertThat(g.reason()).contains("not 'nothing was found'");
    }

    @Test
    @DisplayName("G5: a broken or decaying thesis fails; a deep purchase drift fails too")
    void g5Thesis() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.decayVerdict = "BROKEN";
        assertThat(service.g5ThesisIntact(f.build()).status()).isEqualTo(GateStatus.FAIL);

        f.decayVerdict = "INTACT";
        f.purchaseDriftPoints = -30.0;
        assertThat(service.g5ThesisIntact(f.build()).status()).isEqualTo(GateStatus.FAIL);

        // Drift only counts when the investor actually recorded a purchase thesis to drift from.
        f.convictionRecordExists = Boolean.FALSE;
        assertThat(service.g5ThesisIntact(f.build()).status()).isEqualTo(GateStatus.PASS);

        f.decayVerdict = "STALE";
        assertThat(service.g5ThesisIntact(f.build()).status()).isEqualTo(GateStatus.UNMEASURED);
    }

    @Test
    @DisplayName("G7: a horizon under three years fails; no conviction record is PASS_NO_DATA")
    void g7Horizon() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.holdingHorizonMonths = 12;
        assertThat(service.g7Horizon(f.build()).status()).isEqualTo(GateStatus.FAIL);

        f.convictionRecordExists = Boolean.FALSE;
        assertThat(service.g7Horizon(f.build()).status()).isEqualTo(GateStatus.PASS_NO_DATA);

        f.convictionRecordExists = Boolean.TRUE;
        f.holdingHorizonMonths = null;
        assertThat(service.g7Horizon(f.build()).status()).isEqualTo(GateStatus.PASS_NO_DATA);
    }

    /**
     * B-057. Every auto-generated conviction record carries a seeded 24-month horizon, which is
     * under the 36-month bar — so a gate that reads "the investor's own stated intent" was failing
     * holdings on a number nobody chose. All 46 records on this machine were seeded, and the first
     * live run demoted 14 holdings on it. A default is not a statement, in the same way a null is
     * not a zero.
     */
    @Test
    @DisplayName("B-057: a seeded horizon is PASS_NO_DATA, never a failure")
    void g7DoesNotFailOnASeededDefault() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.holdingHorizonMonths = 24;
        f.horizonStated = Boolean.FALSE;

        CoreDto.Gate g = service.g7Horizon(f.build());
        assertThat(g.status()).isEqualTo(GateStatus.PASS_NO_DATA);
        assertThat(g.reason()).contains("system default");

        // A seeded horizon carries no evidence either, so it cannot help reach the quorum.
        f.forensicMeasured = Boolean.FALSE;
        f.forensicFlagCount = null;
        f.insiderPulseVerdict = null;
        assertThat(classify(f).evidenceGateCount()).isEqualTo(4);
        assertThat(classify(f).provisionalTier()).isEqualTo(CoreTier.UNCLASSIFIED);

        // The same number, actually chosen by the investor, is a real (failing) statement.
        f.horizonStated = Boolean.TRUE;
        assertThat(service.g7Horizon(f.build()).status()).isEqualTo(GateStatus.FAIL);
    }

    // ------------------------------------------------------------------ critical triggers

    @Test
    @DisplayName("A forensic flag, HIGH_RISK, BROKEN or heavy insider selling is a critical trigger")
    void criticalTriggersAreIdentified() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        assertThat(classify(f).criticalTrigger()).isFalse();

        f.forensicFlagCount = 1;
        assertThat(classify(f).criticalTrigger()).isTrue();

        f.forensicFlagCount = 0;
        f.financialQualityVerdict = "HIGH_RISK";
        assertThat(classify(f).criticalTrigger()).isTrue();

        f.financialQualityVerdict = "HIGH_QUALITY";
        f.decayVerdict = "BROKEN";
        assertThat(classify(f).criticalTrigger()).isTrue();

        f.decayVerdict = "INTACT";
        f.insiderPulseVerdict = "STRONG_DISTRIBUTION";
        assertThat(classify(f).criticalTrigger()).isTrue();
    }

    @Test
    @DisplayName("A soft signal is not a critical trigger — it must not bypass hysteresis")
    void softSignalIsNotCritical() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.insiderPulseVerdict = "DISTRIBUTION";     // soft, not STRONG_
        CoreClassification c = classify(f);
        assertThat(c.provisionalTier()).isEqualTo(CoreTier.CORE_WATCH);
        assertThat(c.criticalTrigger()).isFalse();
    }
}
