package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §52.8 - the units guard, and the defect that proved it was needed.
 *
 * <p>The measurement shipped without this, ran over the investor's real book, and produced a full
 * rolling record and a category percentile for an <b>IDCW Payout</b> series measured against
 * Direct-<b>Growth</b> peers. Every number was arithmetically correct and the comparison was
 * meaningless. Nothing threw; the verdict simply read worse than the fund deserved.
 */
class SeriesComparabilityTest {

    @Test
    @DisplayName("an IDCW series is refused, because its NAV is net of every distribution")
    void idcwIsNotComparableWithGrowthPeers() {
        // Verbatim from the live book: "Motilal Oswal ELSS Tax Saver Fund Direct - IDCW Payout".
        SeriesComparability c = SeriesComparability.of(SchemePlanKey.Option.IDCW);

        assertThat(c.comparable()).isFalse();
        assertThat(c.reason())
                .contains("falls by the amount of every payout")
                .contains("understate this fund");
    }

    @Test
    @DisplayName("the refusal names the fix, so it reads as a gap and not as a verdict")
    void refusalPointsAtTheGrowthSeries() {
        // "Not measured" on a fund the investor owns is alarming unless it says why and what would
        // answer it. The Growth series of the same scheme is measurable, and saying so turns a
        // dead end into a next step (SPEC 49.12's lesson: context is not an action).
        assertThat(SeriesComparability.of(SchemePlanKey.Option.IDCW).reason())
                .contains("Growth series of the same scheme would be measurable");
    }

    @Test
    @DisplayName("an unresolved option is refused too - probably-Growth is still an assumption")
    void unspecifiedIsRefusedRatherThanAssumed() {
        SeriesComparability c = SeriesComparability.of(SchemePlanKey.Option.UNSPECIFIED);

        assertThat(c.comparable()).isFalse();
        assertThat(c.reason())
                .as("a default is not a statement - Gotcha 68")
                .contains("Assuming Growth would produce a confident figure from an assumption");
    }

    @Test
    @DisplayName("a null option is refused rather than throwing or passing")
    void nullOptionIsRefused() {
        assertThat(SeriesComparability.of(null).comparable()).isFalse();
    }

    @Test
    @DisplayName("a Growth series is the one thing that passes")
    void growthPasses() {
        SeriesComparability c = SeriesComparability.of(SchemePlanKey.Option.GROWTH);

        assertThat(c.comparable()).isTrue();
        assertThat(c.reason()).contains("comparable with its Growth peers");
    }

    @Test
    @DisplayName("the refusal never reads as a judgement on the fund")
    void refusalIsNeverAVerdict() {
        // The direction of the bias is what makes this dangerous: an IDCW series always reads
        // WORSE, so an unguarded comparison reports a fund the investor owns as a laggard. The
        // refusal text must not itself sound like that finding.
        for (SchemePlanKey.Option o : SchemePlanKey.Option.values()) {
            String reason = SeriesComparability.of(o).reason();
            assertThat(reason).doesNotContain("underperform").doesNotContain("laggard");
        }
    }
}
