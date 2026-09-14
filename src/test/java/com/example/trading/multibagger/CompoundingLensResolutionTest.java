package com.example.trading.multibagger;

import com.example.trading.fundamentals.AnnualFundamentalsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins how the compounding lens picks a screening row when a stock has several symbol spellings
 * (B-088, SPEC 41).
 *
 * <p><b>The rule: a row that cannot answer is not an answer.</b> Screening history is keyed on the
 * NSE symbol while 22 of 33 holdings are BSE-prefixed, so the lens reads across spellings - the
 * composite describes the company, not the listing venue (Gotcha 84). But BSE-keyed rows exist
 * that carry no balance-sheet figures at all, and a plain "first hit wins" picked those over the
 * full NSE row. On the live portfolio that reported RELIANCE, NTPC and NATIONALUM as "not
 * measured" while the screener showed real verdicts for all three - five holdings in total, on
 * the screen the investor makes decisions from.
 *
 * <p>Choosing the row with figures is not choosing a nicer verdict. NOT_MEASURED is the absence
 * of a verdict, not a bad one, so there is nothing to cherry-pick: between two rows that can both
 * answer, exact-spelling-first still wins.
 */
class CompoundingLensResolutionTest {

    private static final LocalDate WHEN = LocalDate.of(2026, 9, 7);

    /** A screening row with the balance-sheet figures the lens needs. */
    private static MultibaggerScoreEntity rich(String symbol) {
        MultibaggerScoreEntity e = new MultibaggerScoreEntity();
        e.setSymbol(symbol);
        e.setScreeningDate(WHEN);
        e.setRocePercent(24.0);
        e.setRoaPercent(11.0);
        e.setCashConversionRatio(1.2);
        e.setDebtToEquity(0.2);
        e.setEarningsConsistencyScore(80);
        e.setFinancialQualityVerdict("HIGH_QUALITY");
        return e;
    }

    /** A row that exists but carries nothing the lens can judge - the B-088 case. */
    private static MultibaggerScoreEntity empty(String symbol) {
        MultibaggerScoreEntity e = new MultibaggerScoreEntity();
        e.setSymbol(symbol);
        e.setScreeningDate(WHEN);
        return e;
    }

    private static CompoundingLensService lens(List<MultibaggerScoreEntity> rows,
                                               List<Object[]> depthRows) {
        MultibaggerScoreRepository scores = mock(MultibaggerScoreRepository.class);
        AnnualFundamentalsRepository fundamentals = mock(AnnualFundamentalsRepository.class);
        when(scores.findRecentForSymbols(anyCollection(), any(LocalDate.class))).thenReturn(rows);
        when(fundamentals.countYearsBySymbol()).thenReturn(depthRows);
        return new CompoundingLensService(scores, fundamentals);
    }

    @Test
    @DisplayName("An empty BSE row must not shadow the NSE row that has the figures")
    void aRowWithNoFiguresIsNotAnAnswer() {
        CompoundingLensService service =
                lens(List.of(empty("BSE:RELIANCE"), rich("NSE:RELIANCE")), List.of());

        CompoundingLensService.Reading r = service.forSymbol("BSE:RELIANCE");

        assertThat(r).isNotNull();
        assertThat(r.symbolAnswered()).isEqualTo("NSE:RELIANCE");
        assertThat(r.result().applicable()).isGreaterThan(0);
        assertThat(r.result().verdict())
                .isNotEqualTo(CompoundingQuality.Verdict.NOT_MEASURED);
        assertThat(r.resolvedAcrossExchange()).isTrue();
    }

    @Test
    @DisplayName("Between two rows that can both answer, the exact spelling still wins")
    void exactSpellingStillWinsWhenBothCanAnswer() {
        // The fix must not become "always prefer NSE" - that would quietly discard a BSE row that
        // was perfectly capable of answering, and the resolution order in Gotcha 84 exists.
        CompoundingLensService service =
                lens(List.of(rich("BSE:TATASTEEL"), rich("NSE:TATASTEEL")), List.of());

        CompoundingLensService.Reading r = service.forSymbol("BSE:TATASTEEL");

        assertThat(r.symbolAnswered()).isEqualTo("BSE:TATASTEEL");
        assertThat(r.resolvedAcrossExchange()).isFalse();
    }

    @Test
    @DisplayName("When no spelling can answer, the honest NOT_MEASURED is still reported")
    void everyRowEmptyStillReportsSomething() {
        // Reporting nothing at all would be wrong too: the stock HAS been screened, and the
        // reader should see "not measured" with a traceable symbol rather than "never screened".
        CompoundingLensService service =
                lens(List.of(empty("BSE:X"), empty("NSE:X")), List.of());

        CompoundingLensService.Reading r = service.forSymbol("BSE:X");

        assertThat(r).isNotNull();
        assertThat(r.result().verdict()).isEqualTo(CompoundingQuality.Verdict.NOT_MEASURED);
        assertThat(r.symbolAnswered()).isNotBlank();
    }

    @Test
    @DisplayName("A stock with no screening row at all yields no reading")
    void neverScreenedYieldsNothing() {
        CompoundingLensService service = lens(List.of(), List.of());
        assertThat(service.forSymbol("NSE:UNKNOWN")).isNull();
        // ...and the bulk map simply omits it, so the caller renders "never screened".
        assertThat(service.forSymbols(List.of("NSE:UNKNOWN"))).isEmpty();
    }

    @Test
    @DisplayName("History depth is found under whichever spelling holds it")
    void depthResolvesAcrossSpellings() {
        // KAJARIACER read "1 year" on the live portfolio because depth was looked up under the
        // holding's BSE symbol while annual_fundamentals stores it under NSE. It has seven.
        CompoundingLensService service = lens(
                List.of(rich("NSE:KAJARIACER")),
                List.<Object[]>of(new Object[] {"NSE:KAJARIACER", 7L}));

        CompoundingLensService.Reading r = service.forSymbol("BSE:KAJARIACER");
        assertThat(r.result().yearsOfAccounts()).isEqualTo(7);
    }

    @Test
    @DisplayName("A whole portfolio resolves in one repository call")
    void bulkPathIsOneQuery() {
        // ~33 holdings x up to 4 spellings would be ~130 queries done per row. The IN query is
        // what keeps the portfolio page inside its load budget.
        MultibaggerScoreRepository scores = mock(MultibaggerScoreRepository.class);
        AnnualFundamentalsRepository fundamentals = mock(AnnualFundamentalsRepository.class);
        when(scores.findRecentForSymbols(anyCollection(), any(LocalDate.class)))
                .thenReturn(List.of(rich("NSE:A"), rich("NSE:B"), rich("NSE:C")));
        when(fundamentals.countYearsBySymbol()).thenReturn(List.of());

        Map<String, CompoundingLensService.Reading> out =
                new CompoundingLensService(scores, fundamentals)
                        .forSymbols(List.of("BSE:A", "NSE:B", "NSE:C"));

        assertThat(out).hasSize(3);
        org.mockito.Mockito.verify(scores, org.mockito.Mockito.times(1))
                .findRecentForSymbols(anyCollection(), any(LocalDate.class));
    }

    @Test
    @DisplayName("A repository failure leaves every stock unread rather than failing the page")
    void repositoryFailureDegradesQuietly() {
        MultibaggerScoreRepository scores = mock(MultibaggerScoreRepository.class);
        AnnualFundamentalsRepository fundamentals = mock(AnnualFundamentalsRepository.class);
        when(scores.findRecentForSymbols(anyCollection(), any(LocalDate.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThat(new CompoundingLensService(scores, fundamentals)
                .forSymbols(List.of("NSE:A"))).isEmpty();
    }
}
