package com.example.trading.watchlist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WatchlistSymbolsTest {

    @Test
    void normalises() {
        assertThat(WatchlistSymbols.normalise("reliance")).isEqualTo("NSE:RELIANCE");
        assertThat(WatchlistSymbols.normalise("  nse:tcs ")).isEqualTo("NSE:TCS");
        assertThat(WatchlistSymbols.normalise("bse:WAAREEENER")).isEqualTo("BSE:WAAREEENER");
        assertThat(WatchlistSymbols.normalise("NSE:KWIL-BE")).isEqualTo("NSE:KWIL");
        assertThat(WatchlistSymbols.normalise("M&M")).isEqualTo("NSE:M&M");
    }

    @Test
    void rejects() {
        assertThatThrownBy(() -> WatchlistSymbols.normalise("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchlistSymbols.normalise(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchlistSymbols.normalise("NFO:NIFTY26SEPFUT")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WatchlistSymbols.normalise("NSE:")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tradingSymbol() {
        assertThat(WatchlistSymbols.tradingSymbol("NSE:RELIANCE")).isEqualTo("RELIANCE");
        assertThat(WatchlistSymbols.tradingSymbol("RELIANCE")).isEqualTo("RELIANCE");
    }
}
