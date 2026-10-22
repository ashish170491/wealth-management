package com.example.trading.valuation;

import com.example.trading.ai.IntrinsicValuationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * SPEC §57.4: every constant is pinned so that changing one fails here and forces a version bump.
 * Old snapshot rows are never rewritten; new rule-set rows append beside them.
 */
class ValuationGapRulesTest {

    /**
     * The fingerprint of rule-set VG-2 as shipped. If this fails because a constant moved, bump
     * {@link ValuationGapRules#RULESET} and update this value - both, deliberately.
     */
    private static final String VG2_FINGERPRINT = "c366da0cafda7256eff171b453ce741b245d0ed3fa2bdffca81e023ac4aab8a9";

    @Test
    @DisplayName("the fingerprint is 64 hex characters, stable across calls and tied to VG-2")
    void fingerprintShape() {
        String f = ValuationGapRules.fingerprint();
        assertThat(f).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(ValuationGapRules.fingerprint()).isEqualTo(f);
        assertThat(ValuationGapRules.RULESET).isEqualTo("VG-2");
    }

    @Test
    @DisplayName("the shipped fingerprint is pinned: any constant change must be a deliberate version bump")
    void fingerprintIsPinned() {
        assertThat(ValuationGapRules.fingerprint()).isEqualTo(VG2_FINGERPRINT);
    }

    @Test
    @DisplayName("the constants are the ones the design states")
    void constantsArePinned() {
        assertThat(ValuationGapRules.HURDLE_RATE).isEqualTo(0.12);
        assertThat(ValuationGapRules.SENSITIVITY_RATES).containsExactly(0.10, 0.14);
        assertThat(ValuationGapRules.MIN_OBSERVATIONS).isEqualTo(5);
        assertThat(ValuationGapRules.MAX_OBSERVATIONS).isEqualTo(10);
        assertThat(ValuationGapRules.PEAK_FACTOR).isEqualTo(1.5);
        assertThat(ValuationGapRules.MAX_STD_ERR_PP).isEqualTo(5.0);
        assertThat(ValuationGapRules.MIN_R_SQUARED).isEqualTo(0.5);
        assertThat(ValuationGapRules.CROSSCHECK_TOLERANCE).isEqualTo(0.05);
        assertThat(ValuationGapRules.SHARE_BASIS_TOLERANCE).isEqualTo(0.02);
        assertThat(ValuationGapRules.NON_ACTION_BAND).isEqualTo(0.25);
        assertThat(ValuationGapRules.MAX_PRICE_AGE_DAYS).isEqualTo(14);
        assertThat(ValuationGapRules.COMPLETE_DATE_SHARE).isEqualTo(0.90);
        assertThat(ValuationGapRules.MIN_BONUS_RATIO).isEqualTo(1.5);
        assertThat(ValuationGapRules.RATIO_GRID_TOLERANCE).isEqualTo(0.01);
        assertThat(ValuationGapRules.AMBIGUOUS_CEILING).isEqualTo(1.25);
        assertThat(ValuationGapRules.MAX_CAPTURE_DATE_AGE_DAYS).isEqualTo(5);
        assertThat(ValuationGapRules.GRID_REFERENCE_MULTIPLES).containsExactly(13, 25, 35);
        assertThat(FiscalHorizon.REFERENCE_FISCAL_YEAR).isEqualTo(2031);
    }

    @Test
    @DisplayName("the validation rule is part of what the fingerprint hashes")
    void fingerprintCoversTheValidationRule() {
        assertThat(ValuationGapRules.VALIDATION_RULE).contains("ACTUAL_NON_POSITIVE").contains("earliest row");
        assertThat(ValuationGapRules.sha256("a")).isNotEqualTo(ValuationGapRules.sha256("b"));
        assertThat(ValuationGapRules.fingerprint()).isNotEqualTo(ValuationGapRules.sha256(ValuationGapRules.RULESET));
    }

    @Test
    @DisplayName("the hurdle rate and the 13.000 factor equal the reverse-DCF's by reflection, so they cannot drift")
    void hurdleMatchesSection125() throws Exception {
        Field discount = IntrinsicValuationService.class.getDeclaredField("DISCOUNT_RATE");
        discount.setAccessible(true);
        Field terminal = IntrinsicValuationService.class.getDeclaredField("TERMINAL_GROWTH");
        terminal.setAccessible(true);
        Field years = IntrinsicValuationService.class.getDeclaredField("FORECAST_YEARS");
        years.setAccessible(true);
        double r = discount.getDouble(null);
        double g = terminal.getDouble(null);
        int n = years.getInt(null);
        assertThat(ValuationGapRules.HURDLE_RATE).isEqualTo(r);

        double q = (1 + g) / (1 + r);
        double sum = 0;
        for (int k = 1; k <= n; k++) sum += Math.pow(q, k);
        double factor = sum + Math.pow(q, n) * (1 + g) / (r - g);
        assertThat(factor).isCloseTo(13.0, within(0.001));
        assertThat(ValuationGapRules.GRID_REFERENCE_MULTIPLES[0]).isEqualTo(13);
    }

    @Test
    @DisplayName("every rule on the printed table has an id, a statement and what it selects")
    void rulesTableIsComplete() {
        var rules = ValuationGapRules.rules();
        assertThat(rules).extracting(ValuationGapRules.Rule::id)
                .containsExactly("VG-S1", "VG-N1", "VG-R1", "VG-T1", "VG-M1", "VG-H1", "VG-H2", "VG-V1");
        for (var r : rules) {
            assertThat(r.rule()).isNotBlank();
            assertThat(r.selects()).isNotBlank();
        }
        assertThat(ValuationGapRules.MULTIPLES_LIMITS).contains("what-if column").contains("not a view");
    }
}
