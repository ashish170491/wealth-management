package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the horizon rule table (SPEC §52.16).
 *
 * <p>The property under test is not "does it give good advice" but <b>does it refuse in the right
 * places</b>. Three refusals matter: no equity category for short money, no answer at all for a
 * pre-2018 bucket whose mandate spans an order of magnitude of duration, and no instruction to
 * transact anywhere in the vocabulary.
 */
class HorizonFitTest {

    @Test
    @DisplayName("no equity category is offered for six-month money, and that is the finding")
    void shortMoneyGetsNoEquity() {
        List<HorizonFit.Read> fits = HorizonFit.fitting(6);

        assertThat(fits).isNotEmpty();
        assertThat(fits)
                .as("equity categories offered for money needed in six months")
                .noneMatch(r -> r.category().assetClass() == AssetClass.EQUITY);

        // And the ones that are offered are the ones SEBI built for it.
        assertThat(fits).extracting(HorizonFit.Read::category)
                .contains(SebiCategory.DEBT_LOW_DURATION, SebiCategory.DEBT_MONEY_MARKET);
    }

    @Test
    @DisplayName("a liquid fund is right for three-month money and wrong for five-year money")
    void theSameCategoryReadsDifferentlyAtDifferentHorizons() {
        assertThat(HorizonFit.forCategory(SebiCategory.DEBT_LIQUID, 2).fit())
                .isEqualTo(HorizonFit.Fit.FITS);

        // Not a mistake and not dangerous - it simply gives up return for safety nobody is using.
        // That is a different finding from "too risky", and must not render the same way.
        assertThat(HorizonFit.forCategory(SebiCategory.DEBT_LIQUID, 60).fit())
                .isEqualTo(HorizonFit.Fit.LONGER_THAN_NEEDED);
    }

    @Test
    @DisplayName("a gilt fund is refused for six-month money though it carries no credit risk")
    void noCreditRiskIsNotTheSameAsNoRisk() {
        // The trap this row exists for: a government-securities fund cannot default, which reads as
        // safe, while carrying the most interest-rate risk in the debt table. Its price falls when
        // rates rise and six months is not long enough for the higher yield to make that back.
        HorizonFit.Read r = HorizonFit.forCategory(SebiCategory.DEBT_GILT, 6);
        assertThat(r.fit()).isEqualTo(HorizonFit.Fit.TOO_VOLATILE_FOR_THIS_HORIZON);
        assertThat(r.basis()).containsIgnoringCase("interest-rate risk");
    }

    @Test
    @DisplayName("the straddling pre-2018 debt bucket is NOT_MEASURED, never squeezed into a band")
    void straddlingBucketIsRefused() {
        // 255 live rows under a header meaning "somewhere between three months and three years".
        // Answering a horizon question from it would be B-047's units error in a new place.
        HorizonFit.Read r = HorizonFit.forCategory(SebiCategory.DEBT, 6);

        assertThat(r.fit()).isEqualTo(HorizonFit.Fit.NOT_MEASURED);
        assertThat(r.bandFromMonth()).isNull();
        assertThat(r.bandToMonth()).isNull();
        assertThat(r.basis()).contains("three months to three years");
        // And it never appears in the fitting list at any horizon.
        for (int m : new int[]{1, 6, 12, 36, 120}) {
            assertThat(HorizonFit.fitting(m)).extracting(HorizonFit.Read::category)
                    .doesNotContain(SebiCategory.DEBT);
        }
    }

    @Test
    @DisplayName("ELSS gets a five-year floor, not three, because the lock-in is per instalment")
    void elssFloorAccountsForTheLastInstalment() {
        // Each instalment is locked three years from the day it goes in, so a monthly plan is not
        // fully free for three years after the last one. Reading the headline "three-year lock-in"
        // as the horizon would let a goal be funded with money that cannot be withdrawn for it.
        assertThat(HorizonFit.forCategory(SebiCategory.ELSS, 36).fit())
                .isEqualTo(HorizonFit.Fit.TOO_VOLATILE_FOR_THIS_HORIZON);
        assertThat(HorizonFit.forCategory(SebiCategory.ELSS, 60).fit())
                .isEqualTo(HorizonFit.Fit.FITS);
        assertThat(HorizonFit.forCategory(SebiCategory.ELSS, 60).basis())
                .contains("five years");
    }

    @Test
    @DisplayName("an exchange-traded fund is refused by name, with the reason")
    void etfIsRefusedWithAReason() {
        // An empty answer reads as "nothing to report". A named refusal reads as "this cannot be
        // done honestly", which is the fact (§52.14's rule for categories with no tracker).
        HorizonFit.Read r = HorizonFit.forCategory(SebiCategory.EQUITY_ETF, 60);
        assertThat(r.fit()).isEqualTo(HorizonFit.Fit.NOT_MEASURED);
        assertThat(r.basis()).contains("market price");
    }

    @Test
    @DisplayName("every equity category carries a band, so none can silently drop out of the table")
    void everyEquityCategoryHasABand() {
        // A category with no band renders NOT_MEASURED, which for equity would look like the app
        // failing to check rather than a deliberate refusal. B-060's shape: a value that is a
        // constant because nothing reached it.
        List<SebiCategory> missing = java.util.Arrays.stream(SebiCategory.values())
                .filter(c -> c.assetClass() == AssetClass.EQUITY && !c.isExchangeTraded())
                .filter(c -> HorizonFit.forCategory(c, 60).bandFromMonth() == null)
                .toList();
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("every named debt and hybrid category carries a band — the generic catch-alls aside")
    void everyNamedDebtAndHybridCategoryHasABand() {
        // The defect the first live run found. Credit Risk and Dynamic Bond had no band, so both
        // rendered NOT_MEASURED - which says "this app has no rule" about the two categories the
        // app has the most pointed view on. Gotcha 44's shape, and exactly why a new verdict is run
        // over real rows before it ships (B-113).
        //
        // The two catch-alls are excluded on purpose: DEBT and HYBRID mean "AMFI's header did not
        // state a mandate", so NOT_MEASURED is the correct and only honest answer there.
        List<SebiCategory> missing = java.util.Arrays.stream(SebiCategory.values())
                .filter(c -> c.assetClass() == AssetClass.DEBT || c.assetClass() == AssetClass.HYBRID)
                .filter(c -> c != SebiCategory.DEBT && c != SebiCategory.HYBRID)
                .filter(c -> !c.isExchangeTraded())
                .filter(c -> HorizonFit.forCategory(c, 24).bandFromMonth() == null)
                .toList();

        assertThat(missing)
                .as("debt/hybrid categories with no horizon band — add one, do not delete the case")
                .isEmpty();
    }

    @Test
    @DisplayName("a credit-risk fund's basis says the horizon is not what decides it")
    void creditRiskNamesTheRiskThatIsNotInTheFeed() {
        // The one category where the band is beside the point: what decides it is whether the
        // borrowers repay, and the credit quality of what a debt fund holds is not in this feed at
        // all. Saying so is the whole value of the row.
        String basis = HorizonFit.forCategory(SebiCategory.DEBT_CREDIT_RISK, 48).basis();
        assertThat(basis).contains("not in this feed");
        assertThat(basis).containsIgnoringCase("repay");
    }

    @Test
    @DisplayName("the short-horizon facts name the bank account, and never say what to do")
    void realitiesStateFactsAndIssueNoInstruction() {
        List<String> r = HorizonFit.realities(2);
        assertThat(String.join(" ", r)).containsIgnoringCase("bank sweep account");

        // The same word-boundary check every vocabulary in this module carries. A bare substring
        // would fail on "the fund holds" and on "a business that sells" - Gotcha 53.
        //
        // "exit load" is SEBI's own name for the charge and appears on every fund page the investor
        // will ever read, so the noun phrase is stripped before matching rather than the wording
        // being bent around it. The bare verb stays banned. This exemption is the same shape as
        // SebiCategory stripping "GROWTH OPTION" as a phrase before a bare "OPTION" could strand
        // the word "GROWTH" - and it caught the real thing on its first run.
        List<String> banned = List.of("buy", "sell", "exit", "add", "avoid", "accumulate",
                "reduce", "apply", "switch", "redeem", "recommend");
        for (int months : new int[]{1, 3, 6, 12, 36, 120}) {
            String all = String.join(" ", HorizonFit.realities(months)).toLowerCase(Locale.ROOT)
                    .replace("exit load", "charge-on-the-way-out");
            for (String word : banned) {
                assertThat(all.matches(".*\\b" + word + "\\b.*"))
                        .as("instruction to transact '" + word + "' at " + months + " months")
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("nothing throws on a null category or an absurd horizon")
    void nullSafe() {
        assertThat(HorizonFit.forCategory(null, 6).fit()).isEqualTo(HorizonFit.Fit.NOT_MEASURED);
        assertThat(HorizonFit.forCategory(SebiCategory.DEBT_LIQUID, 0).fit())
                .isEqualTo(HorizonFit.Fit.FITS);
        assertThat(HorizonFit.fitting(0)).isNotNull();
        assertThat(HorizonFit.fitting(10_000)).isNotNull();
    }
}
