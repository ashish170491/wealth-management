package com.example.trading.learning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the scoring-provenance stamp (SPEC §38.1).
 *
 * <p>Two properties are load-bearing:
 * <ol>
 *   <li><b>Stable</b> — the same configuration must produce the same string on every run and
 *       every machine, or history fragments into versions that differ only by JVM.</li>
 *   <li><b>Sensitive</b> — any score-affecting change must produce a different string. The
 *       failure this exists to prevent is two genuinely different engines sharing one label,
 *       which cannot be undone after the fact.</li>
 * </ol>
 */
class ScoringVersionTest {

    private static Map<String, Object> weights(double technical, double valuation, boolean insiderActionable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("w.technicalMomentum", technical);
        m.put("w.valuation", valuation);
        m.put("insiderPulse.actionable", insiderActionable);
        return m;
    }

    @Test
    @DisplayName("Same configuration produces the same version string")
    void stableAcrossCalls() {
        String a = ScoringVersion.of("mb", 1, weights(0.18, 0.13, false));
        String b = ScoringVersion.of("mb", 1, weights(0.18, 0.13, false));
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("Version carries the engine prefix and code revision up front")
    void readableFormat() {
        String v = ScoringVersion.of("mb", 1, weights(0.18, 0.13, false));
        assertThat(v).startsWith("mb1-");
        assertThat(v).hasSize("mb1-".length() + 6);
    }

    @Test
    @DisplayName("A weight change changes the version — B-019 shifted every composite silently")
    void weightChangeChangesVersion() {
        String before = ScoringVersion.of("mb", 1, weights(0.18, 0.13, false));
        String after = ScoringVersion.of("mb", 1, weights(0.20, 0.11, false));
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("Flipping a shadow-mode flag changes the version")
    void shadowFlagChangesVersion() {
        String shadow = ScoringVersion.of("mb", 1, weights(0.18, 0.13, false));
        String live = ScoringVersion.of("mb", 1, weights(0.18, 0.13, true));
        assertThat(live).isNotEqualTo(shadow);
    }

    @Test
    @DisplayName("Bumping the code revision changes the version even with identical config")
    void codeRevisionChangesVersion() {
        String v1 = ScoringVersion.of("mb", 1, weights(0.18, 0.13, false));
        String v2 = ScoringVersion.of("mb", 2, weights(0.18, 0.13, false));
        assertThat(v2).isNotEqualTo(v1);
    }

    @Test
    @DisplayName("Parameter order is part of the identity, so the map must be insertion-ordered")
    void orderMatters() {
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("a", 1.0);
        forward.put("b", 2.0);
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("b", 2.0);
        reversed.put("a", 1.0);
        assertThat(ScoringVersion.of("mb", 1, forward))
                .isNotEqualTo(ScoringVersion.of("mb", 1, reversed));
    }

    @Test
    @DisplayName("Doubles are canonicalised, so float noise below the 4th decimal is not a new engine")
    void ignoresNoiseBelowFourDecimals() {
        String a = ScoringVersion.of("mb", 1, weights(0.18, 0.13, false));
        String b = ScoringVersion.of("mb", 1, weights(0.180000001, 0.13, false));
        assertThat(b).isEqualTo(a);
    }

    @Test
    @DisplayName("A missing engine yields the explicit unknown marker, never a plausible version")
    void unknownEngine() {
        assertThat(ScoringVersion.of(null, 1, weights(0.18, 0.13, false))).isEqualTo(ScoringVersion.UNKNOWN);
        assertThat(ScoringVersion.of("  ", 1, weights(0.18, 0.13, false))).isEqualTo(ScoringVersion.UNKNOWN);
    }

    @Test
    @DisplayName("Null params are allowed — engines whose scoring lives only in code")
    void nullParams() {
        assertThat(ScoringVersion.of("qd", 1, null)).startsWith("qd1-");
    }
}
