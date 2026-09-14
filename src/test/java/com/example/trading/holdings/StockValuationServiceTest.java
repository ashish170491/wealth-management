package com.example.trading.holdings;

import com.example.trading.ai.NseDataService;
import com.example.trading.marketdata.MarketDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the computed-valuation path introduced by B-018.
 *
 * <p>NSE walled {@code /api/quote-equity}, so PE / market cap / EPS are now derived from
 * integrated-filing XBRL plus a live Kite price instead of being fetched. The arithmetic is
 * load-bearing for the reverse-DCF, the multibagger Valuation dimension, the market-cap
 * bonus and PEG — so it is pinned here against values cross-checked with real share counts.
 *
 * <p>{@code webClientBuilder} is left null on purpose: the NSE call it would build is dead,
 * and the resulting failure is swallowed so the computed path takes over. That is exactly
 * the production behaviour once the circuit breaker opens.
 */
class StockValuationServiceTest {

    private NseDataService nseDataService;
    private MarketDataService marketDataService;
    private StockValuationService service;

    @BeforeEach
    void setUp() {
        nseDataService = mock(NseDataService.class);
        marketDataService = mock(MarketDataService.class);
        service = new StockValuationService(null, nseDataService, marketDataService);
    }

    /** A quarter carrying the fields the computation actually reads. */
    private static NseDataService.QuarterlyResult quarter(Double profitCr, Double eps, Double sharesCr) {
        NseDataService.QuarterlyResult q = new NseDataService.QuarterlyResult();
        q.setProfit(profitCr);
        q.setEps(eps);
        q.setSharesOutstandingCr(sharesCr);
        return q;
    }

    private void givenQuarters(String symbol, List<NseDataService.QuarterlyResult> quarters) {
        when(nseDataService.fetchQuarterlyResults(anyString())).thenReturn(quarters);
    }

    private void givenPrice(double price) {
        when(marketDataService.getCurrentPrice(anyString())).thenReturn(price);
    }

    @Test
    @DisplayName("Market cap = price x exact share count from paid-up capital / face value")
    void marketCapUsesExactShareCount() {
        // ITC: 1,252.95 cr shares (12,529,500,000 paid-up / ₹1 face value), ~₹20,184 cr TTM
        // profit, price ₹269.40. Verified live against ITC's real outstanding share count.
        givenQuarters("ITC", List.of(
                quarter(5046.0, 3.96, 1252.95),
                quarter(5046.0, 3.96, 1252.95),
                quarter(5046.0, 3.96, 1252.95),
                quarter(5046.0, 3.96, 1252.95)));
        givenPrice(269.40);

        StockValuationService.ValuationData data = service.getValuationData("NSE:ITC", "FMCG");

        assertThat(data.getMarketCap()).isCloseTo(337_544.0, within(500.0));
        assertThat(data.getStockPe()).isCloseTo(16.7, within(0.5));
    }

    @Test
    @DisplayName("The exact share count beats back-solving shares from profit/EPS")
    void exactShareCountPreferredOverEpsBackSolve() {
        // If one quarter carries an exceptional item, EPS is inflated and profit/EPS
        // understates the share count — which is how Reliance's market cap came out 18%
        // too high (₹21.0 L cr instead of ₹17.8 L cr) before the exact source was used.
        // Here EPS is deliberately distorted; the paid-up-capital figure must win.
        givenQuarters("RELIANCE", List.of(
                quarter(22_041.0, 99.0, 1353.3),   // EPS wildly overstated vs profit/shares
                quarter(22_041.0, 16.3, 1353.3),
                quarter(22_041.0, 16.3, 1353.3),
                quarter(22_041.0, 16.3, 1353.3)));
        givenPrice(1316.0);

        StockValuationService.ValuationData data = service.getValuationData("NSE:RELIANCE", "Energy");

        // price x 1353.3 cr shares = ~₹17.81 lakh cr, regardless of the bad EPS.
        assertThat(data.getMarketCap()).isCloseTo(1_780_943.0, within(2000.0));
    }

    @Test
    @DisplayName("Banks with null EPS still get PE and market cap")
    void banksWithoutEpsStillResolve() {
        // The BANKING XBRL taxonomy uses different EPS element names, so EPS was null for
        // every bank. PE is therefore derived as marketCap / ttmProfit rather than
        // price / EPS, which keeps it available. HDFCBANK: 1,540.1 cr shares @ ₹726.95.
        givenQuarters("HDFCBANK", List.of(
                quarter(20_258.75, null, 1540.1),
                quarter(20_258.75, null, 1540.1),
                quarter(20_258.75, null, 1540.1),
                quarter(20_258.75, null, 1540.1)));
        givenPrice(726.95);

        StockValuationService.ValuationData data = service.getValuationData("NSE:HDFCBANK", "Banking");

        assertThat(data.getMarketCap()).isCloseTo(1_119_598.0, within(1500.0));
        assertThat(data.getStockPe()).isCloseTo(13.8, within(0.5));
    }

    @Test
    @DisplayName("Fewer than four quarters is annualised rather than under-counted")
    void shortHistoryIsAnnualised() {
        // The integrated-filing system only began ~Mar-2025 (B-017), so short histories are
        // normal. Two quarters must annualise to 4x the average, not sum to a half-year.
        givenQuarters("NEWCO", List.of(
                quarter(100.0, 10.0, 40.0),
                quarter(100.0, 10.0, 40.0)));
        givenPrice(200.0);

        StockValuationService.ValuationData data = service.getValuationData("NSE:NEWCO", "IT");

        // ttmProfit = 400, shares = 40 cr, mcap = 200 x 40 = 8000, PE = 8000/400 = 20
        assertThat(data.getMarketCap()).isCloseTo(8000.0, within(1.0));
        assertThat(data.getStockPe()).isCloseTo(20.0, within(0.1));
    }

    @Test
    @DisplayName("Loss-making companies return no PE rather than a nonsensical one")
    void lossMakingReturnsNoPe() {
        givenQuarters("LOSSCO", List.of(
                quarter(-500.0, -5.0, 100.0),
                quarter(-500.0, -5.0, 100.0),
                quarter(-500.0, -5.0, 100.0),
                quarter(-500.0, -5.0, 100.0)));
        givenPrice(50.0);

        StockValuationService.ValuationData data = service.getValuationData("NSE:LOSSCO", "Metals");

        assertThat(data.getStockPe()).isNull();
        assertThat(data.getMarketCap()).isNull();
    }

    @Test
    @DisplayName("Implausible computed values are discarded, not propagated")
    void outOfBoundsValuesAreRejected() {
        // A tiny EPS relative to price would yield PE in the thousands. Better to return
        // "unknown" (scored neutral) than to let a garbage number rank a stock.
        givenQuarters("WEIRDCO", List.of(
                quarter(1.0, 0.01, 1.0),
                quarter(1.0, 0.01, 1.0),
                quarter(1.0, 0.01, 1.0),
                quarter(1.0, 0.01, 1.0)));
        givenPrice(50_000.0);

        StockValuationService.ValuationData data = service.getValuationData("NSE:WEIRDCO", "Other");

        assertThat(data.getStockPe()).isNull();
    }

    @Test
    @DisplayName("No price means no valuation — never a market cap of zero")
    void missingPriceYieldsNoValuation() {
        givenQuarters("NOPRICE", List.of(quarter(100.0, 10.0, 40.0)));
        when(marketDataService.getCurrentPrice(anyString())).thenReturn(null);

        StockValuationService.ValuationData data = service.getValuationData("NSE:NOPRICE", "IT");

        assertThat(data.getMarketCap()).isNull();
        assertThat(data.getStockPe()).isNull();
    }

    @Test
    @DisplayName("No quarterly filings falls back to sector PE only")
    void noFilingsFallsBackToSectorPeOnly() {
        givenQuarters("UNKNOWNCO", List.of());
        givenPrice(100.0);

        StockValuationService.ValuationData data = service.getValuationData("NSE:UNKNOWNCO", "Pharma");

        assertThat(data).isNotNull();
        assertThat(data.getStockPe()).isNull();
        assertThat(data.getMarketCap()).isNull();
        assertThat(data.getIndustry()).isEqualTo("Pharma");
        assertThat(data.getIndustryPe()).as("hardcoded sector PE still supplied").isNotNull();
    }

    @Test
    @DisplayName("Sector PE becomes a live peer median once enough peers are valued")
    void sectorPeUsesLivePeerMedian() {
        // Below the 5-sample minimum the hardcoded table is used; at or above it the median
        // of computed peer PEs takes over. This is what stops a stale constant (e.g. FMCG
        // 50) from making a PE-16.7 stock look 67% "cheap".
        givenPrice(100.0);
        for (int i = 1; i <= 6; i++) {
            // Each peer: shares 10 cr, ttmProfit 100 cr x i => PE = (100 x 10) / (100 x i)
            when(nseDataService.fetchQuarterlyResults("PEER" + i)).thenReturn(List.of(
                    quarter(25.0 * i, 2.5 * i, 10.0),
                    quarter(25.0 * i, 2.5 * i, 10.0),
                    quarter(25.0 * i, 2.5 * i, 10.0),
                    quarter(25.0 * i, 2.5 * i, 10.0)));
            service.getValuationData("NSE:PEER" + i, "TestSector");
        }

        when(nseDataService.fetchQuarterlyResults("SUBJECT")).thenReturn(List.of());
        StockValuationService.ValuationData subject = service.getValuationData("NSE:SUBJECT", "TestSector");

        // Peer PEs are 1000/100i for i=1..6 => 10, 5, 3.33, 2.5, 2, 1.67; median = (3.33+2.5)/2
        assertThat(subject.getIndustryPe()).isCloseTo(2.92, within(0.1));
    }
}
