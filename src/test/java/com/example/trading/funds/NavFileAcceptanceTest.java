package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §52.7 - whether one day's AMFI file is a usable month-end observation.
 *
 * <p><b>Every pair of numbers below is measured from the live archive.</b> The first version of
 * this rule tested total rows and would have failed the third case here, discarding a good month
 * and shortening every fund's record by one observation in a way no screen could have shown - the
 * B-113 discipline (run a new verdict over real rows and look at the spread) catching a design
 * before it shipped rather than after.
 */
class NavFileAcceptanceTest {

    @Test
    @DisplayName("an ordinary business day is accepted")
    void ordinaryDayAccepted() {
        // 31-Aug-2026: 8,643 rows, 2,377 equity.
        assertThat(NavFileAcceptance.assess(8643, 2377).acceptable()).isTrue();
        // 29-Mar-2019: 10,489 rows, 1,762 equity.
        assertThat(NavFileAcceptance.assess(10489, 1762).acceptable()).isTrue();
    }

    @Test
    @DisplayName("a Saturday is rejected - 8 equity schemes is a silent market, not a valuation")
    void saturdayRejected() {
        // 30-Jun-2018: 481 rows, 8 equity.
        NavFileAcceptance a = NavFileAcceptance.assess(481, 8);

        assertThat(a.acceptable()).isFalse();
        assertThat(a.reason()).contains("8").contains("did not strike a NAV");
    }

    @Test
    @DisplayName("a short weekday file with a full equity cross-section is ACCEPTED - the whole point")
    void shortButCompleteEquityDayAccepted() {
        // 30-Apr-2018: 3,271 rows against 9,553 the previous business day, which looks broken. Its
        // equity rows are 1,687 against 1,707 - the equity universe published normally and only
        // debt schemes are missing. Testing total rows would reject this good month.
        NavFileAcceptance a = NavFileAcceptance.assess(3271, 1687);

        assertThat(a.acceptable()).isTrue();
    }

    @Test
    @DisplayName("the threshold sits in the measured gap, not on a guess about market size")
    void thresholdSitsInTheObservedGap() {
        int worstObservedGoodDay = 1687;   // 30-Apr-2018
        int bestObservedBadDay = 8;        // 30-Jun-2018 (Saturday)

        assertThat(NavFileAcceptance.MIN_EQUITY_ROWS)
                .isLessThan(worstObservedGoodDay)
                .isGreaterThan(bestObservedBadDay);
    }

    @Test
    @DisplayName("a rejection says the date is not worth recording, never that funds had no value")
    void rejectionNeverClaimsFundsAreWorthless() {
        String reason = NavFileAcceptance.assess(481, 8).reason();

        // The distinction this whole class exists to preserve: "we have no observation" and
        // "these funds had no value" must never render alike, because the second is the input the
        // survivorship ledger reads.
        assertThat(reason).doesNotContain("no value")
                .contains("not worth recording");
    }
}
