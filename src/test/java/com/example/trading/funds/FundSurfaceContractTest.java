package com.example.trading.funds;

import com.example.trading.funds.amfi.AmfiNavParser;
import com.example.trading.funds.amfi.AmfiSchemeRow;
import com.example.trading.portfolio.FundAllocationSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire and behavioural contract of the mutual fund record (SPEC §52.2, §52.8).
 *
 * <p>Two claims in §52 are only worth making if they are checkable rather than asserted in a
 * comment: that this module <b>contributes zero points to any score</b>, and that its vocabulary
 * <b>carries no instruction to transact</b>. Both are tested here rather than trusted.
 */
class FundSurfaceContractTest {

    private static final List<Class<?>> FUND_CLASSES = List.of(
            MutualFundConfig.class,
            MutualFundSchemeEntity.class,
            MutualFundHoldingEntity.class,
            MutualFundNavEntity.class,
            MutualFundSyncRunEntity.class,
            AmfiSchemeRow.class,
            AmfiNavParser.ParseResult.class,
            SebiCategory.class,
            SchemePlanKey.class,
            AssetClass.class,
            SchemeStructure.class,
            FundAllocationSource.FundSlice.class,
            // Stage 2 (SPEC 52.7-52.9): the measurement classes. These are the ones where a
            // "score" field would be most tempting and most damaging.
            MutualFundHistoryFetchEntity.class,
            NavFileAcceptance.class,
            RollingWindowRecord.Record.class,
            RollingWindowRecord.Window.class,
            CategoryCrossSection.class,
            PlanCostRead.class,
            SurvivorshipLedger.class,
            SeriesComparability.class
    );

    /**
     * Word boundaries, not substrings. The §51 version of this test failed in its first draft on
     * <em>"whoever <b>sells</b> the servers"</em> (Gotcha 53), and the same trap waits here on
     * phrases like "the fund <b>holds</b>" and "assets under management".
     */
    private static final List<String> TRANSACT_VERBS = List.of(
            "buy", "sell", "exit", "redeem", "subscribe", "apply", "invest",
            "accumulate", "avoid", "book", "trim", "recommend");

    @Test
    @DisplayName("no class in the funds package exposes an actionable, bonus, weight or points field")
    void contributesZeroPointsIsCheckable() {
        // Shadow mode exists for a signal that would otherwise steer the portfolio before it could
        // be judged (Gotcha 30). This module steers nothing, so what it must not acquire is the
        // switch that would let it start. A field named like this is how that begins.
        for (Class<?> c : FUND_CLASSES) {
            for (Field f : c.getDeclaredFields()) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("%s.%s", c.getSimpleName(), f.getName())
                        .doesNotContain("actionable")
                        .doesNotContain("bonus")
                        .doesNotContain("points")
                        .doesNotContain("composite");
                // "weight" is barred as a whole word: a field named `weight` is a scoring weight,
                // whereas an allocation percentage is spelled `percent` or `value` here.
                assertThat(name).as("%s.%s", c.getSimpleName(), f.getName()).isNotEqualTo("weight");
            }
        }
    }

    @Test
    @DisplayName("no enum constant anywhere in the module reads as an instruction to transact")
    void vocabularyCarriesNoInstruction() {
        List<Class<? extends Enum<?>>> enums = List.of(
                SebiCategory.class, AssetClass.class, SchemeStructure.class,
                FundVocabulary.FundRecordVerdict.class, FundVocabulary.CategoryStanding.class,
                FundVocabulary.PlanCostVerdict.class, FundVocabulary.BenchmarkAvailability.class,
                MutualFundHistoryFetchEntity.Status.class);

        for (Class<? extends Enum<?>> e : enums) {
            for (Object constant : e.getEnumConstants()) {
                assertNoTransactVerb(((Enum<?>) constant).name(), e.getSimpleName());
            }
        }
        for (SchemePlanKey.Plan p : SchemePlanKey.Plan.values()) {
            assertNoTransactVerb(p.name(), "Plan");
        }
        for (SchemePlanKey.Option o : SchemePlanKey.Option.values()) {
            assertNoTransactVerb(o.name(), "Option");
        }
        for (MutualFundHoldingEntity.SchemeResolvedFrom r
                : MutualFundHoldingEntity.SchemeResolvedFrom.values()) {
            assertNoTransactVerb(r.name(), "SchemeResolvedFrom");
        }
    }

    @Test
    @DisplayName("no user-facing category label reads as an instruction to transact")
    void labelsCarryNoInstruction() {
        for (SebiCategory c : SebiCategory.values()) {
            assertNoTransactVerb(c.label(), "label of " + c.name());
        }
    }

    @Test
    @DisplayName("every measurement label reads as a past-tense fact, not a noun about a fund")
    void measurementLabelsCarryNoInstruction() {
        for (FundVocabulary.FundRecordVerdict v : FundVocabulary.FundRecordVerdict.values()) {
            assertNoTransactVerb(v.label(), "FundRecordVerdict label");
        }
        for (FundVocabulary.CategoryStanding v : FundVocabulary.CategoryStanding.values()) {
            assertNoTransactVerb(v.label(), "CategoryStanding label");
        }
        for (FundVocabulary.PlanCostVerdict v : FundVocabulary.PlanCostVerdict.values()) {
            assertNoTransactVerb(v.label(), "PlanCostVerdict label");
        }
        for (FundVocabulary.BenchmarkAvailability v : FundVocabulary.BenchmarkAvailability.values()) {
            assertNoTransactVerb(v.label(), "BenchmarkAvailability label");
        }
    }

    @Test
    @DisplayName("the switching caveat states the cost and refuses the arithmetic")
    void switchingCaveatStopsAtTheFact() {
        // Naming the Regular-plan drag without naming that moving plans is a redemption plus a
        // purchase - taxable, possibly exit-loaded - reads as advice (SPEC 52.11 refusal 3). The
        // caveat must say so AND must not go on to tell the reader what to do about it.
        String caveat = PlanCostRead.SWITCHING_CAVEAT;

        assertThat(caveat).contains("redemption").contains("tax").contains("exit load");
        assertThat(caveat).contains("deliberately does not compute");
        assertNoTransactVerb(caveat.replace("exit load", "load"), "SWITCHING_CAVEAT");
    }

    @Test
    @DisplayName("four states, and NOT_COMPARABLE is not folded into NOT_MEASURED")
    void theFourStatesAllExist() {
        // Collapsing these throws away a fund's own good record because this app has not loaded an
        // index, or reports a four-scheme category as a quartile (Gotcha 121, 68).
        assertThat(FundVocabulary.FundRecordVerdict.values())
                .contains(FundVocabulary.FundRecordVerdict.NOT_MEASURED,
                        FundVocabulary.FundRecordVerdict.NOT_COMPARABLE);
        assertThat(FundVocabulary.CategoryStanding.values())
                .contains(FundVocabulary.CategoryStanding.NOT_MEASURED,
                        FundVocabulary.CategoryStanding.TOO_FEW_PEERS);
        assertThat(FundVocabulary.BenchmarkAvailability.NOT_HELD.isAnswerable()).isFalse();
        assertThat(FundVocabulary.BenchmarkAvailability.NOT_SINGULAR.isAnswerable()).isFalse();
        assertThat(FundVocabulary.BenchmarkAvailability.HELD_PROXY.isAnswerable()).isTrue();
    }

    @Test
    @DisplayName("a small-cap mandate never resolves to an index this app merely happens to hold")
    void noIndexIsSubstitutedForAMandateItDoesNotMatch() {
        // Substituting a midcap index for a small-cap category is B-047 in index clothing: two
        // different things compared, and the difference reported as skill.
        assertThat(FundLensService.benchmarkAvailability(SebiCategory.SMALL_CAP))
                .isEqualTo(FundVocabulary.BenchmarkAvailability.NOT_HELD);
        assertThat(FundLensService.benchmarkAvailability(SebiCategory.FLEXI_CAP))
                .isEqualTo(FundVocabulary.BenchmarkAvailability.NOT_HELD);

        // And the two kinds of refusal stay distinct: one is our gap, one is the question's shape.
        assertThat(FundLensService.benchmarkAvailability(SebiCategory.SECTORAL_THEMATIC))
                .isEqualTo(FundVocabulary.BenchmarkAvailability.NOT_SINGULAR);
    }

    @Test
    @DisplayName("the config has no switch that would turn this into a scoring signal")
    void configHasNoActionableSwitch() {
        List<String> names = java.util.Arrays.stream(MutualFundConfig.class.getDeclaredFields())
                .map(Field::getName)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
        assertThat(names)
                .as("a flag here is how 'contributes zero points' quietly stops being true")
                .noneMatch(n -> n.contains("actionable") || n.contains("bonus")
                        || n.contains("points") || n.equals("weight"));
    }

    @Test
    @DisplayName("every money field on a holding is a nullable wrapper, never a primitive")
    void moneyFieldsAreNullable() {
        // A primitive collapses "did not look" into "looked and found zero". On a fund whose broker
        // reports no cost basis, a primitive invested value would report the whole position as gain.
        for (String field : List.of("units", "averagePrice", "lastPrice",
                "investedValue", "currentValue", "pnl", "pnlPercent")) {
            Field f = declared(MutualFundHoldingEntity.class, field);
            assertThat(f.getType())
                    .as("%s must be a wrapper so 'unknown' and 'zero' stay distinct", field)
                    .isEqualTo(BigDecimal.class);
        }
    }

    @Test
    @DisplayName("scheme NAV is nullable — a fund that did not publish is not a fund worth nothing")
    void navIsNullable() {
        assertThat(declared(MutualFundSchemeEntity.class, "nav").getType()).isEqualTo(BigDecimal.class);
        assertThat(declared(AmfiSchemeRow.class, "nav").getType()).isEqualTo(BigDecimal.class);
    }

    private static Field declared(Class<?> c, String name) {
        try {
            return c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Expected field " + c.getSimpleName() + "." + name
                    + " — a rename here silently blanks a column on every fund surface", e);
        }
    }

    private static void assertNoTransactVerb(String text, String where) {
        if (text == null) return;
        String lower = text.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (String verb : TRANSACT_VERBS) {
            assertThat(Pattern.compile("\\b" + verb + "\\b").matcher(lower).find())
                    .as("%s (%s) must carry no instruction to transact — SPEC §19, §20 rule 10", where, text)
                    .isFalse();
        }
    }
}
