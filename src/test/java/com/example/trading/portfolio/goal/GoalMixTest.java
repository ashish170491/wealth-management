package com.example.trading.portfolio.goal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the goal mix rule table (SPEC §53.2).
 *
 * <p>The mix is the one investment decision in this app that needs no forecast, which is why it is
 * the one the app is willing to make. The tests are therefore about the <b>boundaries and the
 * refusals</b>, not about whether 50/50 at six years is wise.
 */
class GoalMixTest {

    @Test
    @DisplayName("money needed inside three years gets no equity at all")
    void shortGoalsGetNoEquity() {
        for (int months : new int[]{0, 1, 6, 12, 24, 35}) {
            GoalMix.Mix mix = GoalMix.forHorizon(months);
            assertThat(mix.equityPct())
                    .as("equity share at " + months + " months")
                    .isZero();
            assertThat(mix.debtPct()).isEqualTo(100.0);
        }
        // The boundary itself is the first month equity appears.
        assertThat(GoalMix.forHorizon(36).equityPct()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("the equity share rises with the horizon and never passes the stated cap")
    void theRampIsMonotonicAndCapped() {
        double previous = -1;
        for (int months = 0; months <= 360; months++) {
            GoalMix.Mix mix = GoalMix.forHorizon(months);
            assertThat(mix.equityPct())
                    .as("equity share at " + months + " months must never fall as the horizon grows")
                    .isGreaterThanOrEqualTo(previous);
            assertThat(mix.equityPct()).isLessThanOrEqualTo(GoalMix.MAX_EQUITY_PCT);
            assertThat(mix.equityPct() + mix.debtPct()).isCloseTo(100.0, within(1e-9));
            previous = mix.equityPct();
        }
    }

    @Test
    @DisplayName("the floor is the same constant HorizonFit uses, not a second copy of the number")
    void oneFloorNotTwo() {
        // Gotcha 85 at its smallest: two copies of one rule are free to drift, and the drift here
        // would put a goal's money in equity at a horizon the fund screen refuses it at, in the
        // same app, on two screens the reader sees together.
        assertThat(GoalMix.NO_EQUITY_BELOW_MONTHS)
                .isEqualTo(com.example.trading.funds.HorizonFit.EQUITY_FLOOR_MONTHS);
    }

    @Test
    @DisplayName("every reason is plain English and none of them tells the reader to transact")
    void vocabularyCarriesNoInstruction() {
        List<String> banned = List.of("buy", "sell", "exit", "avoid", "accumulate",
                "reduce", "redeem", "recommend", "should");
        for (int months : new int[]{0, 6, 24, 36, 60, 84, 120, 240}) {
            String text = GoalMix.forHorizon(months).reason().toLowerCase(Locale.ROOT);
            for (String word : banned) {
                assertThat(text.matches(".*\\b" + word + "\\b.*"))
                        .as("instruction '" + word + "' in the reason at " + months + " months")
                        .isFalse();
            }
        }
        // And the caveats must say the percentages are a rule rather than a measurement, or the
        // reader takes a judgement for evidence (§43.4's discipline).
        assertThat(String.join(" ", GoalMix.caveats()))
                .contains("stated rule rather than a measurement");
    }

    @Test
    @DisplayName("the caveats name the tax and the charge, and refuse to calculate either")
    void switchingIsNamedNotCalculated() {
        // §52.8's PlanCostRead rule, one level up: naming a move without naming that it is a sale
        // and a purchase reads as advice, and calculating the tax would be advice.
        String all = String.join(" ", GoalMix.caveats());
        assertThat(all).contains("sale and a purchase");
        assertThat(all).contains("taxable");
    }

    @Test
    @DisplayName("a past date reads as zero months rather than a negative mix")
    void pastDatesAreSafe() {
        GoalMix.Mix mix = GoalMix.forHorizon(-12);
        assertThat(mix.months()).isZero();
        assertThat(mix.equityPct()).isZero();
        assertThat(mix.reason()).isNotBlank();
    }
}
