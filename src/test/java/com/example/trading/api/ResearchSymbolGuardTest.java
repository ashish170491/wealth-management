package com.example.trading.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §39.5 / B-073 — {@code GET /api/research/{symbol}} is a catch-all over an expensive,
 * email-sending operation, so a retired sibling path lands on it instead of 404-ing.
 *
 * <p>That is not hypothetical: removing {@code GET /api/research/market-direction} on 2026-09-03
 * left the old URL returning <b>200</b> while running a full 15-dimension research pass on a
 * "stock" called {@code market-direction}. Verified in the live log before this guard existed.
 */
class ResearchSymbolGuardTest {

    @Test
    @DisplayName("Real symbols are accepted, in every shape the app uses")
    void acceptsRealSymbols() {
        for (String s : new String[]{"NSE:RELIANCE", "BSE:INFY", "RELIANCE", "BAJAJ-AUTO",
                "NSE:BAJAJ-AUTO", "M&M", "NSE:M&M", "NSE:KWIL-BE", "NSE:NIFTY 50", "3MINDIA"}) {
            assertThat(StockResearchController.looksLikeSymbol(s)).as("%s", s).isTrue();
        }
    }

    /**
     * The bug itself, plus every other kebab path that could plausibly be added and later retired
     * under /api/research. Lower-case is the discriminator: every real tradingsymbol is upper-case.
     */
    @Test
    @DisplayName("Retired and mistyped sub-paths are refused before any work happens")
    void rejectsNonSymbolPaths() {
        for (String s : new String[]{"market-direction", "discover", "universe", "levels",
                "earnings", "shareholding", "capital-efficiency", "valuation", "analyst", "capex",
                "reliance", "NSE:reliance"}) {
            assertThat(StockResearchController.looksLikeSymbol(s)).as("%s", s).isFalse();
        }
    }

    /** Empty, absurd and punctuation-only inputs must never reach the research service. */
    @Test
    @DisplayName("Blank, oversized and letterless inputs are refused")
    void rejectsDegenerateInput() {
        assertThat(StockResearchController.looksLikeSymbol(null)).isFalse();
        assertThat(StockResearchController.looksLikeSymbol("")).isFalse();
        assertThat(StockResearchController.looksLikeSymbol("   ")).isFalse();
        assertThat(StockResearchController.looksLikeSymbol("NSE:")).isFalse();
        assertThat(StockResearchController.looksLikeSymbol("12345")).isFalse();
        assertThat(StockResearchController.looksLikeSymbol("---")).isFalse();
        assertThat(StockResearchController.looksLikeSymbol("A".repeat(41))).isFalse();
    }
}
