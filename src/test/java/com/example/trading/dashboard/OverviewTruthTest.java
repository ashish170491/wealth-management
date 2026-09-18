package com.example.trading.dashboard;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.portfolio.AllocationDto;
import com.example.trading.portfolio.AllocationService;
import com.example.trading.portfolio.PortfolioProfileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The landing page's two measured claims: what today's move is worth, and what needs attention.
 *
 * <p>Both were wrong on the live book on 2026-09-18, and both failed in the direction that
 * flatters or distracts rather than the one that alarms - which is why they survived months of
 * daily reading. Every figure quoted below was measured from the running app, not invented; the
 * SME review that found B-120 notes that its own invented cases all passed while the real ones
 * did not (the Gotcha 126a lesson), so these cases are taken from the real book.
 */
class OverviewTruthTest {

    // ------------------------------------------------------------------ B-120

    private static HoldingsEntity holding(String symbol, int qty, double currentPrice, double closePrice) {
        HoldingsEntity h = new HoldingsEntity();
        h.setSymbol(symbol);
        h.setQuantity(qty);
        h.setCurrentPrice(currentPrice);
        h.setClosePrice(closePrice);
        return h;
    }

    @Test
    @DisplayName("B-120: the day's move is worth quantity times the per-share change, not the per-share change")
    void dayChangeValueIsThePosition() {
        // Live row: 102 shares, listed at 146, trading at 172.75. The tile used to report Rs 26.75.
        assertThat(holding("BSE:LCCPROJECT", 102, 172.75, 146.0).getDayChangeValue())
                .isCloseTo(2728.5, within(0.01));
    }

    @Test
    @DisplayName("B-120: a one-share position at a high price must not outweigh a large position at a low one")
    void perShareDeltasDoNotDecideThePortfolioTotal() {
        // The exact shape that produced the wrong sign: CPPLUS is one share of an expensive stock,
        // LCCPROJECT is a hundred shares of a cheap one. Per share the first dominates; in money
        // the second does, by a factor of fifteen.
        HoldingsEntity expensiveSingleShare = holding("NSE:CPPLUS", 1, 3375.5, 3547.7);
        HoldingsEntity cheapLargePosition = holding("BSE:LCCPROJECT", 102, 172.75, 146.0);

        double perShareSum = (3375.5 - 3547.7) + (172.75 - 146.0);
        double moneySum = expensiveSingleShare.getDayChangeValue() + cheapLargePosition.getDayChangeValue();

        assertThat(perShareSum).isNegative();          // what the tile used to say
        assertThat(moneySum).isPositive();             // what actually happened
        assertThat(moneySum).isCloseTo(2556.3, within(0.01));
    }

    @Test
    @DisplayName("B-120: no previous close is an UNKNOWN move, never a zero one")
    void missingCloseIsNotZero() {
        // A zero here would be counted into a portfolio total and drag it toward nothing while
        // looking measured - SPEC 21 rule 7, and the reason this returns a wrapper type.
        assertThat(holding("NSE:JUSTLISTED", 50, 210.0, 0.0).getDayChangeValue()).isNull();
        assertThat(holding("NSE:NOPRICE", 50, 0.0, 200.0).getDayChangeValue()).isNull();
    }

    @Test
    @DisplayName("B-120: a fall is reported as a fall, scaled by the size of the position")
    void aFallScalesWithThePosition() {
        assertThat(holding("NSE:DOWN", 200, 95.0, 100.0).getDayChangeValue())
                .isCloseTo(-1000.0, within(0.01));
    }

    // ------------------------------------------------------------------ B-122

    private static AllocationDto.DriftBucket bucket(String key, double target, double actual, String alert) {
        return new AllocationDto.DriftBucket(
                AllocationService.BUCKET_SECTOR, key, target, actual, actual - target,
                target > 0 ? Math.abs(actual - target) / target : 0.0, alert);
    }

    /** The live shape: every bucket breaching, against targets nobody chose. */
    private static List<AllocationDto.DriftBucket> everyBucketBreaching() {
        return List.of(
                bucket("IT", 20.0, 0.43, AllocationService.ALERT_OVER_TOLERANCE),
                bucket("BANKING", 25.0, 3.77, AllocationService.ALERT_OVER_TOLERANCE),
                bucket("METALS", 10.0, 15.15, AllocationService.ALERT_OVER_TOLERANCE),
                bucket("PHARMA", 10.0, 16.60, AllocationService.ALERT_OVER_TOLERANCE));
    }

    private static AllocationDto.DriftResponse drift(boolean targetsStated) {
        return new AllocationDto.DriftResponse("Default Portfolio", 247047.1, 30,
                everyBucketBreaching(), 0.0, List.of(), targetsStated);
    }

    @Test
    @DisplayName("A seeded allocation profile raises ONE row asking to be set, never one per bucket")
    void unstatedTargetsRaiseASingleInfoRow() {
        List<DashboardDto.AttentionItem> items =
                DashboardService.attention(List.of(), List.of(), drift(false), null);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).kind()).isEqualTo("ALLOCATION_TARGETS_UNSET");
        // INFO, not WARNING: nothing is wrong with the portfolio - something is unknown about the
        // question being asked of it. A default is not a statement (Gotcha 68).
        assertThat(items.get(0).severity()).isEqualTo("INFO");
        assertThat(items).noneMatch(i -> "ALLOCATION_DRIFT".equals(i.kind()));
        // It must carry the figure it is withholding, so the reader can disagree with it.
        assertThat(items.get(0).detail()).contains("4 of your 4");
    }

    @Test
    @DisplayName("Targets the investor actually set are reported bucket by bucket, as before")
    void statedTargetsStillRaiseEveryBreach() {
        List<DashboardDto.AttentionItem> items =
                DashboardService.attention(List.of(), List.of(), drift(true), null);

        assertThat(items).hasSize(4);
        assertThat(items).allMatch(i -> "ALLOCATION_DRIFT".equals(i.kind()));
        assertThat(items).noneMatch(i -> "ALLOCATION_TARGETS_UNSET".equals(i.kind()));
    }

    @Test
    @DisplayName("An unset profile whose buckets all sit inside tolerance says nothing at all")
    void unstatedButInToleranceIsSilent() {
        AllocationDto.DriftResponse quiet = new AllocationDto.DriftResponse(
                "Default Portfolio", 247047.1, 30,
                List.of(bucket("PHARMA", 10.0, 10.2, AllocationService.ALERT_IN_TOLERANCE)),
                0.0, List.of(), false);

        assertThat(DashboardService.attention(List.of(), List.of(), quiet, null)).isEmpty();
    }

    @Test
    @DisplayName("targetsStated: null means unknown and is never read as a statement")
    void nullTargetsStatedIsNotAStatement() {
        PortfolioProfileEntity legacy = new PortfolioProfileEntity();
        legacy.setName("Default Portfolio");
        assertThat(legacy.getTargetsStated()).isNull();
        assertThat(AllocationService.targetsStated(legacy)).isFalse();

        legacy.setTargetsStated(true);
        assertThat(AllocationService.targetsStated(legacy)).isTrue();
    }

    @Test
    @DisplayName("A sell-rated holding is still raised, whatever the allocation profile says")
    void exitSignalsAreUnaffected() {
        HoldingsEntity selling = holding("BSE:NMDC", 10, 70.0, 71.0);
        selling.setRecommendation("SELL");
        selling.setPnlPercent(-4.2);

        List<DashboardDto.AttentionItem> items =
                DashboardService.attention(List.of(selling), List.of(), drift(false), null);

        assertThat(items).extracting(DashboardDto.AttentionItem::kind)
                .containsExactlyInAnyOrder("EXIT_SIGNAL", "ALLOCATION_TARGETS_UNSET");
        // Severity ordering must still put the real finding first.
        assertThat(items.get(0).kind()).isEqualTo("EXIT_SIGNAL");
    }
}
