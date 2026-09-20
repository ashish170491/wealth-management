package com.example.trading.funds;

import com.example.trading.funds.FundVocabulary.CategoryStanding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC §52.8 - where a fund sat inside its own SEBI category, and when that must not be answered. */
class CategoryCrossSectionTest {

    @Test
    @DisplayName("Contra has four schemes in the live file, so it gets no quartile - ever")
    void tooFewPeersIsARefusalNotAGap() {
        // Measured on the live file after the spelling merge and plan de-duplication: CONTRA 4.
        // A quartile over four schemes is arithmetic, and it would be the most confident thing on
        // the screen.
        Map<Long, Double> four = new LinkedHashMap<>();
        four.put(1L, 18.0);
        four.put(2L, 14.0);
        four.put(3L, 11.0);
        four.put(4L, 9.0);

        CategoryCrossSection c = CategoryCrossSection.of(SebiCategory.CONTRA, 1L, four);

        assertThat(c.standing()).isEqualTo(CategoryStanding.TOO_FEW_PEERS);
        assertThat(c.percentile()).as("null, never a number computed from four peers").isNull();
        assertThat(c.subjectReturnPct())
                .as("the fund's own return is still shown - it is unranked, not unmeasured")
                .isEqualTo(18.0);
        assertThat(c.reason()).contains("arithmetic rather than a standing");
    }

    @Test
    @DisplayName("a fund in a real category is placed, and the reason names the denominator")
    void placedAgainstEnoughPeers() {
        Map<Long, Double> peers = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            peers.put((long) i, (double) i);   // 0..39
        }
        peers.put(99L, 35.0);                  // beats 36 of the 41

        CategoryCrossSection c = CategoryCrossSection.of(SebiCategory.MID_CAP, 99L, peers);

        assertThat(c.standing()).isEqualTo(CategoryStanding.UPPER_QUARTILE);
        assertThat(c.peersMeasured()).isEqualTo(41);
        assertThat(c.reason()).contains("of 41 schemes").contains("exactly the same month ends");
    }

    @Test
    @DisplayName("the reason states that every peer is one plan and one option")
    void peerSetIsOnePlanPerScheme() {
        Map<Long, Double> peers = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            peers.put((long) i, (double) i);
        }

        CategoryCrossSection c = CategoryCrossSection.of(SebiCategory.FLEXI_CAP, 5L, peers);

        // Without the one-plan rule a fund appears in its own peer group two or four times at two
        // or four different returns - a doubled population that shifts every percentile in it while
        // looking entirely normal. B-096's arithmetic in a new file.
        assertThat(c.reason()).contains("no fund appears twice");
    }

    @Test
    @DisplayName("a subject with no comparable return is NOT_MEASURED, and says that judges nothing")
    void subjectWithoutAReturnIsNotMeasured() {
        Map<Long, Double> peers = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            peers.put((long) i, (double) i);
        }

        CategoryCrossSection c = CategoryCrossSection.of(SebiCategory.SMALL_CAP, 999L, peers);

        assertThat(c.standing()).isEqualTo(CategoryStanding.NOT_MEASURED);
        assertThat(c.reason()).contains("says nothing about how it did");
    }

    @Test
    @DisplayName("the bottom of a real category is LOWER_QUARTILE, not TOO_FEW_PEERS")
    void aBadRecordIsNotARefusal() {
        Map<Long, Double> peers = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            peers.put((long) i, (double) i);
        }
        peers.put(99L, -5.0);

        CategoryCrossSection c = CategoryCrossSection.of(SebiCategory.ELSS, 99L, peers);

        assertThat(c.standing()).isEqualTo(CategoryStanding.LOWER_QUARTILE);
        assertThat(c.percentile()).isZero();
    }

    @Test
    @DisplayName("a null peer return is skipped rather than counted as zero")
    void nullPeerReturnsAreSkipped() {
        Map<Long, Double> peers = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            peers.put((long) i, (double) i);
        }
        peers.put(500L, null);
        peers.put(501L, null);

        CategoryCrossSection c = CategoryCrossSection.of(SebiCategory.LARGE_CAP, 10L, peers);

        // A peer whose return could not be computed did not come last - it was not measured
        // (Gotcha 21). Counting it as zero drags every percentile above it upward.
        assertThat(c.peersMeasured()).isEqualTo(20);
    }
}
