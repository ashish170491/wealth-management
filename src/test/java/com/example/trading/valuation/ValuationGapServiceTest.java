package com.example.trading.valuation;

import com.example.trading.earnings.QuarterlyResultRepository;
import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.example.trading.fundamentals.AnnualFundamentalsRepository;
import com.example.trading.holdings.SymbolVariants;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.trading.valuation.ValuationFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SPEC §57.3 and §57.6: which rows answer. Partial screening dates are never complete, the newest
 * spelling's row wins and is never blended, one spelling's annual record is used whole, and a stock
 * in no table is a 404 while a stock in one table is a refusal.
 */
class ValuationGapServiceTest {

    private MultibaggerScoreRepository scores;
    private AnnualFundamentalsRepository annual;
    private QuarterlyResultRepository quarterly;
    private ValuationGapService service;

    @BeforeEach
    void setUp() {
        scores = mock(MultibaggerScoreRepository.class);
        annual = mock(AnnualFundamentalsRepository.class);
        quarterly = mock(QuarterlyResultRepository.class);
        service = new ValuationGapService(scores, annual, quarterly);
    }

    private static MultibaggerScoreEntity row(String symbol, LocalDate date, double price, double cap) {
        return MultibaggerScoreEntity.builder().symbol(symbol).screeningDate(date).currentPrice(price)
                .marketCapCrores(cap).industry("Consumer Durables").dcfVerdict("FAIRLY_VALUED")
                .dcfImpliedGrowthPercent(14.0).scoringVersion("v1").build();
    }

    // ------------------------------------------------------------------- the complete date

    @Test
    @DisplayName("a date with 86, 2 or 1 rows is not a complete screening run when 300 ran the same fortnight")
    void partialDatesAreNeverComplete() {
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        counts.put(LocalDate.of(2026, 10, 1), 2L);
        counts.put(LocalDate.of(2026, 10, 8), 310L);
        counts.put(LocalDate.of(2026, 10, 10), 1L);
        counts.put(LocalDate.of(2026, 10, 12), 86L);
        assertThat(ValuationGapService.pickCompleteDate(counts)).contains(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("the 90% line: 279 of 310 is complete, 278 is not; the latest qualifying date wins")
    void ninetyPercentBoundary() {
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        counts.put(LocalDate.of(2026, 10, 8), 310L);
        counts.put(LocalDate.of(2026, 10, 9), 279L);
        counts.put(LocalDate.of(2026, 10, 12), 278L);
        assertThat(ValuationGapService.pickCompleteDate(counts)).contains(LocalDate.of(2026, 10, 9));
    }

    @Test
    @DisplayName("no screening in the window, or only empty dates, has no complete date")
    void noCompleteDate() {
        assertThat(ValuationGapService.pickCompleteDate(Map.of())).isEmpty();
        assertThat(ValuationGapService.pickCompleteDate(Map.of(LocalDate.of(2026, 10, 8), 0L))).isEmpty();
    }

    @Test
    @DisplayName("latestCompleteDate reads only the last fortnight and counts each date once")
    void latestCompleteDateUsesTheWindow() {
        LocalDate old = TODAY.minusDays(40);
        LocalDate good = TODAY.minusDays(3);
        LocalDate partial = TODAY.minusDays(1);
        when(scores.findScreeningDates()).thenReturn(List.of(partial, good, old));
        when(scores.countByScreeningDate(partial)).thenReturn(1L);
        when(scores.countByScreeningDate(good)).thenReturn(300L);
        when(scores.countByScreeningDate(old)).thenReturn(5000L);   // outside the window: must be ignored
        assertThat(service.latestCompleteDate(TODAY)).contains(good);
    }

    // ----------------------------------------------------------------------- row selection

    @Test
    @DisplayName("price row: the newest across spellings wins (B-150) and is never blended")
    void newestSpellingWins() {
        var nse = row("NSE:ABC", LocalDate.of(2026, 4, 18), 100, 1000);
        var bse = row("BSE:ABC", LocalDate.of(2026, 10, 12), 130, 1300);
        var picked = ValuationGapService.pickScoreRow(List.of(nse, bse), SymbolVariants.candidates("NSE:ABC"), null);
        assertThat(picked.getSymbol()).isEqualTo("BSE:ABC");
    }

    @Test
    @DisplayName("price row: on a tie the exact spelling wins")
    void exactSpellingBreaksATie() {
        var d = LocalDate.of(2026, 10, 12);
        var nse = row("NSE:ABC", d, 100, 1000);
        var bse = row("BSE:ABC", d, 100, 1000);
        assertThat(ValuationGapService.pickScoreRow(List.of(bse, nse), SymbolVariants.candidates("NSE:ABC"), null)
                .getSymbol()).isEqualTo("NSE:ABC");
        assertThat(ValuationGapService.pickScoreRow(List.of(bse, nse), SymbolVariants.candidates("BSE:ABC"), null)
                .getSymbol()).isEqualTo("BSE:ABC");
    }

    @Test
    @DisplayName("price row: a row after the latest complete date (a single-stock screen) is ignored")
    void rowsAfterTheCompleteDateAreIgnored() {
        var complete = LocalDate.of(2026, 10, 9);
        var good = row("NSE:ABC", complete, 100, 1000);
        var single = row("BSE:ABC", LocalDate.of(2026, 10, 12), 999, 9999);
        var picked = ValuationGapService.pickScoreRow(List.of(good, single), SymbolVariants.candidates("NSE:ABC"), complete);
        assertThat(picked.getCurrentPrice()).isEqualTo(100);
    }

    @Test
    @DisplayName("annual spelling: the record that ends latest wins, then the longer one, then the first in order")
    void annualSpelling() {
        List<AnnualFundamentalsEntity> rows = new ArrayList<>();
        rows.addAll(ValuationFixtures.steady("NSE:ABC", 2026, 8, 10, 0.1, 10));
        rows.addAll(ValuationFixtures.steady("BSE:ABC", 2026, 1, 10, 0.1, 10));
        assertThat(ValuationGapService.pickAnnualSpelling(rows, SymbolVariants.candidates("BSE:ABC"))).isEqualTo("NSE:ABC");

        List<AnnualFundamentalsEntity> newer = new ArrayList<>();
        newer.addAll(ValuationFixtures.steady("NSE:ABC", 2025, 8, 10, 0.1, 10));
        newer.addAll(ValuationFixtures.steady("BSE:ABC", 2026, 5, 10, 0.1, 10));
        assertThat(ValuationGapService.pickAnnualSpelling(newer, SymbolVariants.candidates("NSE:ABC"))).isEqualTo("BSE:ABC");

        List<AnnualFundamentalsEntity> tie = new ArrayList<>();
        tie.addAll(ValuationFixtures.steady("NSE:ABC", 2026, 6, 10, 0.1, 10));
        tie.addAll(ValuationFixtures.steady("BSE:ABC", 2026, 6, 10, 0.1, 10));
        assertThat(ValuationGapService.pickAnnualSpelling(tie, SymbolVariants.candidates("BSE:ABC"))).isEqualTo("BSE:ABC");
        assertThat(ValuationGapService.pickAnnualSpelling(List.of(), SymbolVariants.candidates("NSE:ABC"))).isNull();
    }

    // -------------------------------------------------------------------- 404 versus refusal

    @Test
    @DisplayName("G0: a stock in neither table under any spelling is empty (HTTP 404)")
    void unknownStockIsEmpty() {
        when(scores.findScreeningDates()).thenReturn(List.of());
        when(scores.findLatestForSymbolsSince(anyCollection(), any())).thenReturn(List.of());
        when(annual.findHistoryForSymbols(anyCollection())).thenReturn(List.of());
        assertThat(service.read("NSE:NOSUCH", TODAY)).isEmpty();
    }

    @Test
    @DisplayName("accounts without a screening row is a 200 refusal (NEVER_SCREENED), not a 404")
    void accountsOnlyIsARefusal() {
        when(scores.findScreeningDates()).thenReturn(List.of());
        when(scores.findLatestForSymbolsSince(anyCollection(), any())).thenReturn(List.of());
        when(annual.findHistoryForSymbols(anyCollection()))
                .thenReturn(ValuationFixtures.steady("NSE:NEWCO", 2026, 6, 10, 0.1, 10));
        when(quarterly.findForSymbols(anyList())).thenReturn(List.of());
        var r = service.read("NSE:NEWCO", TODAY);
        assertThat(r).isPresent();
        assertThat(r.get().reasonCode()).isEqualTo(ValuationReading.Reason.NEVER_SCREENED);
    }

    @Test
    @DisplayName("read() measures an eligible stock end to end through the repositories")
    void readsThroughRepositories() {
        var in = ValuationFixtures.eligible("NSE:GOOD");
        when(scores.findScreeningDates()).thenReturn(List.of(TODAY));
        when(scores.countByScreeningDate(TODAY)).thenReturn(300L);
        when(scores.findLatestForSymbolsSince(anyCollection(), any()))
                .thenReturn(List.of(row("NSE:GOOD", TODAY, 800, 8000)));
        when(annual.findHistoryForSymbols(anyCollection())).thenReturn(in.annual());
        when(quarterly.findForSymbols(anyList())).thenReturn(in.quarters());
        var r = service.read("NSE:GOOD", TODAY);
        assertThat(r).isPresent();
        assertThat(r.get().status()).isEqualTo(ValuationReading.Status.MEASURED);
        assertThat(r.get().resolvedFrom()).isEqualTo("NSE:GOOD");
    }

    // --------------------------------------------------------------------------------- readAll

    @Test
    @DisplayName("readAll reads one stock once when it is screened under both exchange spellings, preferring NSE")
    void readAllDeduplicatesSpellings() {
        var in = ValuationFixtures.eligible("NSE:GOOD");
        when(scores.findByScreeningDateOrderByCompositeScoreDesc(TODAY)).thenReturn(List.of(
                row("BSE:GOOD", TODAY, 800, 8000), row("NSE:GOOD", TODAY, 800, 8000)));
        when(annual.findHistoryForSymbols(anyCollection())).thenReturn(in.annual());
        when(quarterly.findForSymbols(anyList())).thenReturn(in.quarters());
        when(scores.findLatestForSymbolsSince(anyCollection(), any())).thenReturn(List.of());
        var batch = service.readAll(TODAY, TODAY);
        assertThat(batch.readings()).hasSize(1);
        assertThat(batch.readings().get(0).symbol()).isEqualTo("NSE:GOOD");
        assertThat(batch.failed()).isZero();
    }

    @Test
    @DisplayName("readAll counts a stock whose reading throws as failed and still returns the others")
    void readAllSurvivesOneBadStock() {
        var in = ValuationFixtures.eligible("NSE:GOOD");
        var bad = row("NSE:BAD", TODAY, 800, 8000);
        bad.setIndustry("Consumer Durables");
        when(scores.findByScreeningDateOrderByCompositeScoreDesc(TODAY)).thenReturn(List.of(
                row("NSE:GOOD", TODAY, 800, 8000), bad));
        // A null fiscal year in an annual row is exactly the kind of malformed row that must not sink the pass.
        List<AnnualFundamentalsEntity> annualRows = new ArrayList<>(in.annual());
        AnnualFundamentalsEntity poisoned = ValuationFixtures.year("NSE:BAD", 2026, 10.0, 1.0, 10.0, true);
        poisoned.setFiscalYear(null);
        annualRows.add(poisoned);
        when(annual.findHistoryForSymbols(anyCollection())).thenReturn(annualRows);
        when(quarterly.findForSymbols(anyList())).thenReturn(in.quarters());
        when(scores.findLatestForSymbolsSince(anyCollection(), any())).thenReturn(List.of());
        var batch = service.readAll(TODAY, TODAY);
        assertThat(batch.readings().size() + batch.failed()).isEqualTo(2);
        assertThat(batch.readings()).anyMatch(r -> r.symbol().equals("NSE:GOOD")
                && r.status() == ValuationReading.Status.MEASURED);
    }

    // ------------------------------------------------------------------------------- coverage

    @Test
    @DisplayName("the funnel counts each stock once, by the first gate it failed, and reports the withheld share")
    void funnel() {
        var good = ValuationGapService.assemble(ValuationFixtures.eligible("NSE:GOOD"));
        var never = ValuationGapService.assemble(new ValuationGapService.Inputs("NSE:N", "NSE:N", TODAY, null,
                ValuationFixtures.eligible("NSE:N").annual(), List.of(), false));
        var c = ValuationGapService.summarise(TODAY, List.of(good, never, never), java.time.LocalDateTime.now());
        assertThat(c.screenedStocks()).isEqualTo(3);
        assertThat(c.measured()).isEqualTo(1);
        assertThat(c.unavailable()).isEqualTo(2);
        assertThat(c.firstFailingGate()).containsEntry("NEVER_SCREENED", 2);
        assertThat(c.trendWithheld()).isZero();
        assertThat(c.trendWithheldSharePct()).isEqualTo(0.0);
    }
}
