package com.example.trading.fundamentals;

import com.example.trading.ai.NseDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two rules that make the universe backfill converge and stay honest (SPEC §32.6).
 *
 * <p><b>Convergence.</b> A symbol whose archive holds three annual filings is finished at three.
 * If completion were judged on "does it have ten years", the batch picker — which orders by
 * fewest years held — would return to the shallowest symbols every single day and never reach
 * the ones nobody has touched. That is the natural failure of the obvious implementation, so it
 * is the first thing pinned here.
 *
 * <p><b>Point-in-time honesty.</b> An estimated availability date must never be readable as a
 * filed one, and a real filed date must never be overwritten by an estimate on a later re-run.
 * Without that, a back-test of the §43 track record silently gains up to five months of
 * look-ahead — and look-ahead bias always flatters, because the lens "knew" the result before
 * the price moved.
 */
class FundamentalsBackfillTest {

    private static FundamentalsHistoryService.BackfillOutcome outcome(
            int listed, int written, int skippedBasis, boolean listingFailed) {
        return new FundamentalsHistoryService.BackfillOutcome(
                "NSE:TEST", listed, written, skippedBasis, 0, listingFailed, Boolean.TRUE);
    }

    @Test
    @DisplayName("A symbol with only three filings, all stored, is COMPLETE")
    void shallowButFullyStoredIsComplete() {
        assertThat(outcome(3, 3, 0, false).complete()).isTrue();
    }

    @Test
    @DisplayName("Years skipped to keep one reporting basis still count toward completion")
    void basisSkipsCountAsSeen() {
        // Gotcha 73: RELIANCE FY2022 exists only as a standalone filing between consolidated
        // years. It is deliberately left out, and re-fetching would leave it out again — so it
        // is done, not outstanding. A gap the downstream checks already handle beats a
        // fabricated collapse they cannot detect.
        assertThat(outcome(10, 9, 1, false).complete()).isTrue();
    }

    @Test
    @DisplayName("Filings that failed to parse leave the symbol incomplete")
    void unparsedYearsAreStillOwed() {
        assertThat(outcome(13, 4, 0, false).complete()).isFalse();
    }

    @Test
    @DisplayName("A failed listing is never complete, however many rows already exist")
    void listingFailureIsNotCompletion() {
        // A fetch failure must not be recorded as a fact about the business.
        assertThat(outcome(0, 0, 0, true).complete()).isFalse();
        assertThat(outcome(10, 10, 0, true).complete()).isFalse();
    }

    @Test
    @DisplayName("An empty archive is not completion either — it is a finding to record")
    void emptyArchiveIsNotComplete() {
        assertThat(outcome(0, 0, 0, false).complete()).isFalse();
    }

    @Test
    @DisplayName("A filed broadcast date is parsed and marked as filed")
    void filedDateIsUsedAndFlaggedReal() {
        assertThat(FundamentalsHistoryService.parseFilingDate("30-May-2024"))
                .isEqualTo(LocalDate.of(2024, 5, 30));
        assertThat(FundamentalsHistoryService.parseFilingDate("2024-05-30"))
                .isEqualTo(LocalDate.of(2024, 5, 30));
        assertThat(FundamentalsHistoryService.parseFilingDate("30-05-2024 18:42:00"))
                .isEqualTo(LocalDate.of(2024, 5, 30));
    }

    @Test
    @DisplayName("An unreadable date yields null, never today and never the year end")
    void unparseableDateIsNull() {
        // Falling back to "now" would date a decade-old filing as public today, which is the
        // look-ahead bias this field exists to remove, in its worst possible form.
        assertThat(FundamentalsHistoryService.parseFilingDate(null)).isNull();
        assertThat(FundamentalsHistoryService.parseFilingDate("  ")).isNull();
        assertThat(FundamentalsHistoryService.parseFilingDate("not a date")).isNull();
    }

    @Test
    @DisplayName("The estimate is later than the year end, so it can never leak the future")
    void estimateErrsLate() {
        // SEBI LODR Reg 33(3) allows 60 days; the estimate is deliberately more conservative.
        // Erring early is look-ahead bias; erring late merely shrinks the usable sample.
        assertThat(FundamentalsHistoryService.ESTIMATED_FILING_LAG_MONTHS).isGreaterThanOrEqualTo(2);
        LocalDate yearEnd = LocalDate.of(2024, 3, 31);
        LocalDate estimated = yearEnd.plusMonths(FundamentalsHistoryService.ESTIMATED_FILING_LAG_MONTHS);
        assertThat(estimated).isAfter(yearEnd);
    }

    @Test
    @DisplayName("Pacing is real and cannot be configured down to nothing")
    void pacingHasAFloor() {
        long original = NseDataService.getArchivePaceMs();
        try {
            NseDataService.setArchivePaceMs(0);
            // A zero would mean the javadoc claims pacing while there is none — which is exactly
            // the state defect 7.1 found, and this floor is what stops it being reachable again.
            assertThat(NseDataService.getArchivePaceMs()).isGreaterThan(0);
            NseDataService.setArchivePaceMs(1500);
            assertThat(NseDataService.getArchivePaceMs()).isEqualTo(1500);
        } finally {
            NseDataService.setArchivePaceMs(original);
        }
    }

    @Test
    @DisplayName("A batch is a small fraction of the universe, so one run cannot flood NSE")
    void batchDefaultIsBounded() {
        FundamentalsBackfillConfig config = new FundamentalsBackfillConfig();
        assertThat(config.getBatchSize()).isBetween(1, 60);
        assertThat(config.getPaceMs()).isGreaterThanOrEqualTo(500L);
        assertThat(config.getMaxAttempts()).isGreaterThan(0);
        // Enabled by default, unlike a new scoring signal: this changes no score and produces no
        // verdict, so Gotcha 30's shadow rule does not apply to it.
        assertThat(config.isEnabled()).isTrue();
    }

    // ------------------------------------------------------- deadline window (B-091)

    @Test
    @DisplayName("The batch yields to the afternoon NSE jobs, and only to them")
    void deadlineBlocksOnlyTheContentionWindow() {
        java.time.LocalTime stop = java.time.LocalTime.of(13, 30);

        // Inside the window: the 14:00 screening and 14:45 insider capture share the one NSE
        // session, so the backfill must stand aside.
        assertThat(FundamentalsBackfillService.inContentionWindow(java.time.LocalTime.of(14, 0), stop)).isTrue();
        assertThat(FundamentalsBackfillService.inContentionWindow(java.time.LocalTime.of(15, 44), stop)).isTrue();

        // Before it: the 11:30 batch's own slot.
        assertThat(FundamentalsBackfillService.inContentionWindow(java.time.LocalTime.of(11, 30), stop)).isFalse();

        // After it: nothing is left to contend with, so an evening catch-up must run. Written
        // open-ended, this returned true and a manual run after a missed 11:30 fire silently
        // did nothing while reporting a successful batch of zero symbols.
        assertThat(FundamentalsBackfillService.inContentionWindow(java.time.LocalTime.of(17, 0), stop)).isFalse();
        assertThat(FundamentalsBackfillService.inContentionWindow(java.time.LocalTime.of(23, 30), stop)).isFalse();
        assertThat(FundamentalsBackfillService.inContentionWindow(java.time.LocalTime.of(2, 0), stop)).isFalse();
    }
}
