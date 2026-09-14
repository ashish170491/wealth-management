package com.example.trading.watchlist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gotcha 22 applied to the watchlist: a missing leg is null, never 0.0%. */
class WatchlistReturnMathTest {

    @Test @DisplayName("return is null when price-at-add is missing (seeded rows)")
    void nullFrom() {
        assertThat(WatchlistReturnMath.pctReturn(null, 110.0)).isNull();
    }

    @Test @DisplayName("a 0.0 'price' is the no-price sentinel, not a price")
    void zeroIsNoPrice() {
        assertThat(WatchlistReturnMath.pctReturn(0.0, 110.0)).isNull();
        assertThat(WatchlistReturnMath.pctReturn(100.0, 0.0)).isNull();
        assertThat(WatchlistReturnMath.pctReturn(100.0, -1.0)).isNull();
    }

    @Test @DisplayName("arithmetic and rounding")
    void arithmetic() {
        assertThat(WatchlistReturnMath.pctReturn(100.0, 112.34)).isEqualTo(12.3);
        assertThat(WatchlistReturnMath.pctReturn(200.0, 150.0)).isEqualTo(-25.0);
    }

    @Test @DisplayName("excess is null when Nifty is unmeasured, while the stock return stays measured")
    void excess() {
        assertThat(WatchlistReturnMath.excess(12.3, null)).isNull();
        assertThat(WatchlistReturnMath.excess(null, 3.0)).isNull();
        assertThat(WatchlistReturnMath.excess(12.3, 3.1)).isEqualTo(9.2);
    }
}
