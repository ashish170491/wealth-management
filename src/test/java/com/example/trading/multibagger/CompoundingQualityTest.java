package com.example.trading.multibagger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the compounding-quality lens (SPEC §41).
 *
 * <p>The properties defended here are the ones that make the badge honest rather than flattering:
 * an unmeasured check never counts as a pass, a not-applicable check leaves the denominator
 * instead of being scored, a serious accounting flag outranks every good number, and a business
 * is never called a compounder on the strength of missing data.
 */
class CompoundingQualityTest {

    /** A business that clears every check — modelled on the live CAMS/CDSL profile. */
    private CompoundingQuality.Input excellent() {
        return new CompoundingQuality.Input(
                30.0,   // ROCE
                12.0,   // ROA
                1.2,    // cash conversion
                0.05,   // debt / equity
                70,     // earnings consistency
                1.5,    // gross-margin trend
                "STEADY", null, "HIGH_QUALITY", 1);
    }

    private CompoundingQuality.Input with(CompoundingQuality.Input b,
                                          Double roce, Double cash, Double de,
                                          Integer cons, Double margin) {
        return new CompoundingQuality.Input(roce, b.roaPercent(), cash, de, cons, margin,
                b.capexVerdict(), b.forensicFlags(), b.financialQualityVerdict(), b.yearsOfAccounts());
    }

    @Nested
    @DisplayName("the verdict")
    class VerdictRules {

        @Test
        @DisplayName("all five checks passing earns the badge")
        void allPass() {
            CompoundingQuality.Result r = CompoundingQuality.evaluate(excellent());
            assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
            assertThat(r.isCompounder()).isTrue();
            assertThat(r.passed()).isEqualTo(5);
            assertThat(r.applicable()).isEqualTo(5);
        }

        @Test
        @DisplayName("one failed check is enough to lose the badge — it is a gate, not a score")
        void oneFailureLosesTheBadge() {
            // Everything excellent except the return on capital, which is the whole engine.
            CompoundingQuality.Result r = CompoundingQuality.evaluate(
                    with(excellent(), 9.0, 1.2, 0.05, 70, 1.5));
            assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.PARTIAL);
            assertThat(r.isCompounder()).isFalse();
            assertThat(r.reason()).contains("4 of 5");
        }

        @Test
        @DisplayName("mostly failing reads NO")
        void mostlyFailing() {
            CompoundingQuality.Result r = CompoundingQuality.evaluate(
                    with(excellent(), 4.0, 0.2, 3.0, 10, -6.0));
            assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.NO);
            assertThat(r.passed()).isZero();
        }
    }

    @Nested
    @DisplayName("absence of evidence")
    class NullDiscipline {

        @Test
        @DisplayName("an unmeasured check is never a pass, and never a fail either")
        void unmeasuredIsNeitherPassNorFail() {
            CompoundingQuality.Result r = CompoundingQuality.evaluate(
                    with(excellent(), null, 1.2, 0.05, 70, 1.5));
            assertThat(r.applicable()).isEqualTo(4);   // the unmeasured one left the denominator
            assertThat(r.passed()).isEqualTo(4);
            assertThat(r.gates()).anySatisfy(g -> {
                if (g.key().equals("capitalReturn")) {
                    assertThat(g.status()).isEqualTo(CompoundingQuality.GateStatus.NOT_MEASURED);
                }
            });
        }

        @Test
        @DisplayName("too little measured is NOT_MEASURED, never NO — a data gap is not a failing grade")
        void tooLittleMeasured() {
            CompoundingQuality.Input sparse = new CompoundingQuality.Input(
                    null, null, null, null, 70, null, null, null, null, null);
            CompoundingQuality.Result r = CompoundingQuality.evaluate(sparse);
            assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.NOT_MEASURED);
            assertThat(r.reason()).contains("not a mark against the business");
        }

        @Test
        @DisplayName("a null input object does not throw")
        void nullInput() {
            assertThat(CompoundingQuality.evaluate(null).verdict())
                    .isEqualTo(CompoundingQuality.Verdict.NOT_MEASURED);
        }

        @Test
        @DisplayName("four measured checks all passing is still enough for a non-financial")
        void fourOfFiveIsEnough() {
            // The bar is "all applicable checks, and at least four of them" — so one genuinely
            // unmeasurable check does not disqualify an otherwise clean business.
            CompoundingQuality.Result r = CompoundingQuality.evaluate(
                    with(excellent(), 30.0, 1.2, 0.05, 70, null));
            assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
            assertThat(r.applicable()).isEqualTo(4);
        }

        @Test
        @DisplayName("three measured checks is NOT enough for a non-financial to earn the badge")
        void threeIsNotEnoughForANonFinancial() {
            // Guards the obvious way to fake a compounder: publish almost nothing.
            CompoundingQuality.Result r = CompoundingQuality.evaluate(
                    with(excellent(), 30.0, 1.2, null, 70, null));
            assertThat(r.applicable()).isEqualTo(3);
            assertThat(r.verdict()).isNotEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
        }
    }

    @Nested
    @DisplayName("lenders are judged differently")
    class Financials {

        private CompoundingQuality.Input bank(Double roa) {
            return new CompoundingQuality.Input(
                    null, roa, 1.1, null, 70, null,
                    "NA_FINANCIAL", null, "HIGH_QUALITY", 4);
        }

        @Test
        @DisplayName("leverage, margins and cash conversion are not applicable, and leave the denominator")
        void notApplicableLeavesTheDenominator() {
            CompoundingQuality.Result r = CompoundingQuality.evaluate(bank(1.9));
            // Was 3 until 2026-09-08. Cash conversion joined the not-applicable set for lenders:
            // a bank's operating cash flow tracks deposits and lending, and requiring it left
            // every one of the 24 financials in the universe permanently NOT_MEASURED. The
            // property being pinned is unchanged - NOT_APPLICABLE leaves the denominator - and
            // note this bank *does* publish a cash-conversion figure (1.1), which is deliberately
            // still not counted, so a lender's denominator does not vary with what NSE tagged.
            assertThat(r.applicable()).isEqualTo(2);
            assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
            assertThat(r.gates()).filteredOn(g -> g.key().equals("internallyFunded"))
                    .allMatch(g -> g.status() == CompoundingQuality.GateStatus.NOT_APPLICABLE);
            assertThat(r.gates()).filteredOn(g -> g.key().equals("realCash"))
                    .allMatch(g -> g.status() == CompoundingQuality.GateStatus.NOT_APPLICABLE);
        }

        @Test
        @DisplayName("a weak lender still fails on return on assets")
        void weakBankFails() {
            assertThat(CompoundingQuality.evaluate(bank(0.4)).verdict())
                    .isNotEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
        }

        @Test
        @DisplayName("NOT_APPLICABLE is not a pass: it is excluded, not counted")
        void notApplicableIsNotAPass() {
            CompoundingQuality.Result r = CompoundingQuality.evaluate(bank(1.9));
            assertThat(r.passed()).isEqualTo(2).isEqualTo(r.applicable());
        }
    }

    @Nested
    @DisplayName("the accounts outrank the numbers computed from them")
    class Disqualifiers {

        @Test
        @DisplayName("a HIGH forensic flag disqualifies however good every ratio is")
        void highForensicFlagDisqualifies() {
            CompoundingQuality.Input in = new CompoundingQuality.Input(
                    40.0, 20.0, 2.0, 0.0, 95, 5.0,
                    "INVESTING", "RECEIVABLES:HIGH", "HIGH_QUALITY", 8);
            CompoundingQuality.Result r = CompoundingQuality.evaluate(in);
            assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.NO);
            assertThat(r.reason()).contains("serious question about the accounts");
        }

        @Test
        @DisplayName("a MEDIUM flag is a caution, not a disqualification (Gotcha 77)")
        void mediumFlagDoesNotDisqualify() {
            CompoundingQuality.Input in = new CompoundingQuality.Input(
                    30.0, 12.0, 1.2, 0.05, 70, 1.5,
                    "STEADY", "RECEIVABLES:MEDIUM", "HIGH_QUALITY", 1);
            assertThat(CompoundingQuality.evaluate(in).verdict())
                    .isEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
        }

        @Test
        @DisplayName("an INFO flag is not a stop")
        void infoFlagIsNotAStop() {
            CompoundingQuality.Input in = new CompoundingQuality.Input(
                    30.0, 12.0, 1.2, 0.05, 70, 1.5,
                    "INFO_AUDITOR_CHANGE:INFO", null, "HIGH_QUALITY", 1);
            assertThat(CompoundingQuality.evaluate(in).verdict())
                    .isEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
        }

        @Test
        @DisplayName("a HIGH_RISK balance sheet disqualifies — survival precedes compounding")
        void highRiskDisqualifies() {
            CompoundingQuality.Input in = new CompoundingQuality.Input(
                    40.0, 20.0, 2.0, 0.0, 95, 5.0,
                    "INVESTING", null, "HIGH_RISK", 8);
            CompoundingQuality.Result r = CompoundingQuality.evaluate(in);
            assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.NO);
            assertThat(r.reason()).contains("high-risk");
        }
    }

    @Nested
    @DisplayName("what it refuses to claim")
    class Honesty {

        @Test
        @DisplayName("years of accounts is carried through, and null stays null rather than zero")
        void yearsCarriedThrough() {
            assertThat(CompoundingQuality.evaluate(excellent()).yearsOfAccounts()).isEqualTo(1);
            CompoundingQuality.Input unknown = new CompoundingQuality.Input(
                    30.0, 12.0, 1.2, 0.05, 70, 1.5, "STEADY", null, "HIGH_QUALITY", null);
            assertThat(CompoundingQuality.evaluate(unknown).yearsOfAccounts()).isNull();
        }

        @Test
        @DisplayName("a long history does not by itself earn the badge, and a short one does not block it")
        void historyDoesNotDecideTheVerdict() {
            // The gates are evaluated on the latest year either way. History length is reported so
            // the reader can discount the verdict — it is never an input to it, because pretending
            // otherwise would imply persistence was checked when it was not.
            CompoundingQuality.Input oneYear = new CompoundingQuality.Input(
                    30.0, 12.0, 1.2, 0.05, 70, 1.5, "STEADY", null, "HIGH_QUALITY", 1);
            CompoundingQuality.Input eightYears = new CompoundingQuality.Input(
                    30.0, 12.0, 1.2, 0.05, 70, 1.5, "STEADY", null, "HIGH_QUALITY", 8);
            assertThat(CompoundingQuality.evaluate(oneYear).verdict())
                    .isEqualTo(CompoundingQuality.evaluate(eightYears).verdict());
        }

        @Test
        @DisplayName("capex is context, never a gate — an asset-light business is not penalised")
        void capexIsContextNotAGate() {
            // The rejected design gated on capexVerdict, which would have failed CAMS, CDSL and
            // Oracle Financial for not needing factories — the highest-ROCE businesses measured.
            CompoundingQuality.Result steady = CompoundingQuality.evaluate(excellent());
            CompoundingQuality.Input investing = new CompoundingQuality.Input(
                    30.0, 12.0, 1.2, 0.05, 70, 1.5, "INVESTING", null, "HIGH_QUALITY", 1);
            assertThat(steady.verdict())
                    .isEqualTo(CompoundingQuality.evaluate(investing).verdict());
            assertThat(steady.gates()).hasSize(5);
            assertThat(steady.capexContext()).isNotNull();
        }

        @Test
        @DisplayName("every gate carries the figure behind it, so no verdict is unexplained")
        void everyGateExplainsItself() {
            for (CompoundingQuality.Gate g : CompoundingQuality.evaluate(excellent()).gates()) {
                assertThat(g.detail()).as("detail for " + g.key()).isNotBlank();
                assertThat(g.question()).as("question for " + g.key()).isNotBlank();
            }
        }
    }

    // ------------------------------------------------------- lenders (SPEC 41.4, 2026-09-08)

    /** HDFC Bank's real figures from the 2026-09-08 screening run. */
    private static CompoundingQuality.Input lender(Double cashConversion, Integer consistency,
                                                   Double roa) {
        return new CompoundingQuality.Input(
                null,            // ROCE is not computed for a lender (SPEC 12.8)
                roa,
                cashConversion,
                null,            // borrowings are its raw material
                consistency,
                null,            // no gross margin
                "NA_FINANCIAL",  // the bank marker (Gotcha 88)
                null, null, 6);
    }

    @Test
    @DisplayName("A lender is judged on the two checks that apply to it, not left unmeasurable")
    void lenderIsJudgedOnItsTwoApplicableChecks() {
        // Before this rule every one of the 24 financials in the universe read NOT_MEASURED:
        // three of five gates do not apply and cash conversion is published for only 4 of 24,
        // leaving 2 applicable against a floor of 3. A whole business class silently unjudgeable.
        CompoundingQuality.Result r = CompoundingQuality.evaluate(lender(null, 60, 1.5));

        assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
        assertThat(r.applicable()).isEqualTo(2);
        assertThat(r.passed()).isEqualTo(2);
        // The thinner basis is stated, not hidden behind an identical badge.
        assertThat(r.reason()).contains("two checks rather than four");
    }

    @Test
    @DisplayName("A lender's cash conversion is not-applicable, never a missing measurement")
    void lenderCashConversionIsNotApplicable() {
        CompoundingQuality.Result r = CompoundingQuality.evaluate(lender(null, 60, 1.5));
        CompoundingQuality.Gate cash = r.gates().stream()
                .filter(g -> g.key().equals("realCash")).findFirst().orElseThrow();

        // A bank's operating cash flow tracks deposits and lending, so OCF/profit measures the
        // loan book's swings rather than earnings quality. Calling that "not measured" implied a
        // filing gap that would never close.
        assertThat(cash.status()).isEqualTo(CompoundingQuality.GateStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("A lender that fails a check it can be judged on does not get the badge")
    void weakLenderStillFails() {
        // ROA below the 1.5% bar: one of two applicable checks fails.
        CompoundingQuality.Result r = CompoundingQuality.evaluate(lender(null, 60, 0.4));
        assertThat(r.verdict()).isNotEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
        assertThat(r.applicable()).isEqualTo(2);
    }

    @Test
    @DisplayName("A lender with nothing measurable is still NOT_MEASURED, not a free pass")
    void lenderWithNoFiguresIsStillUnmeasured() {
        CompoundingQuality.Result r = CompoundingQuality.evaluate(lender(null, null, null));
        assertThat(r.verdict()).isEqualTo(CompoundingQuality.Verdict.NOT_MEASURED);
        assertThat(r.applicable()).isZero();
    }

    @Test
    @DisplayName("The lender rule does not loosen the bar for an ordinary business")
    void nonFinancialBarIsUnchanged() {
        // Three applicable checks, all passed - still short of the four a non-financial needs.
        CompoundingQuality.Result r = CompoundingQuality.evaluate(new CompoundingQuality.Input(
                25.0, null, 1.2, 0.2, null, null, "STEADY", null, null, 5));
        assertThat(r.applicable()).isEqualTo(3);
        assertThat(r.verdict()).isNotEqualTo(CompoundingQuality.Verdict.COMPOUNDER);
    }
}
