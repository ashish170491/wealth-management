package com.example.trading.watchlist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** B-063 — the Kite quote wrapper must be unwrapped before reading last_price. */
class WatchlistQuoteUnwrapTest {

    @Test @DisplayName("Kite wrapper {status, data:{symbol:{...}}} resolves to the per-symbol map")
    void unwrapsWrapper() {
        Map<String, Object> raw = Map.of("status", "success",
                "data", Map.of("NSE:TATAPOWER", Map.of("last_price", 350.05)));
        Map<String, Object> q = WatchlistAnalysisService.unwrapQuote(raw, "NSE:TATAPOWER");
        assertThat(q).isNotNull();
        assertThat(q.get("last_price")).isEqualTo(350.05);
    }

    @Test @DisplayName("a single-entry data map keyed differently (series suffix alias) still resolves")
    void singleEntryFallback() {
        Map<String, Object> raw = Map.of("status", "success", "data", Map.of("NSE:KWIL", Map.of("last_price", 12.5)));
        assertThat(WatchlistAnalysisService.unwrapQuote(raw, "NSE:KWIL-BE").get("last_price")).isEqualTo(12.5);
    }

    @Test @DisplayName("an empty data map (invalid symbol) is null, not a map without a price")
    void emptyDataIsNull() {
        Map<String, Object> raw = Map.of("status", "success", "data", Map.of());
        assertThat(WatchlistAnalysisService.unwrapQuote(raw, "NSE:ZZZ")).isNull();
        assertThat(WatchlistAnalysisService.unwrapQuote(null, "NSE:ZZZ")).isNull();
    }

    @Test @DisplayName("a bare per-symbol map passes through")
    void barePassThrough() {
        Map<String, Object> bare = Map.of("last_price", 99.0);
        assertThat(WatchlistAnalysisService.unwrapQuote(bare, "NSE:X")).isSameAs(bare);
    }
}
