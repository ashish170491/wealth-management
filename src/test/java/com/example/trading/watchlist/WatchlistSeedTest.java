package com.example.trading.watchlist;

import com.example.trading.watchlist.WatchlistSeedRunner.SeedDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC §37.5 — the seed is idempotent and never resurrects a removed row. */
class WatchlistSeedTest {

    @Test @DisplayName("no row → INSERT")
    void insert() {
        assertThat(WatchlistSeedRunner.decide(null)).isEqualTo(SeedDecision.INSERT);
    }

    @Test @DisplayName("legacy analysis row with no added-on → STAMP")
    void stamp() {
        WatchlistEntity legacy = new WatchlistEntity();
        legacy.setSymbol("NSE:TCS");
        assertThat(WatchlistSeedRunner.decide(legacy)).isEqualTo(SeedDecision.STAMP);
    }

    @Test @DisplayName("already stamped → SKIP, so a second boot changes nothing")
    void skipStamped() {
        WatchlistEntity row = new WatchlistEntity();
        row.setAddedOn(LocalDate.of(2026, 8, 1));
        row.setActive(true);
        row.setSource("MANUAL");
        row.setPriceAtAdd(100.0);
        assertThat(WatchlistSeedRunner.decide(row)).isEqualTo(SeedDecision.SKIP);
    }

    @Test @DisplayName("a removed row is never resurrected, even without an added-on date")
    void neverResurrect() {
        WatchlistEntity removed = new WatchlistEntity();
        removed.setActive(false);
        assertThat(WatchlistSeedRunner.decide(removed)).isEqualTo(SeedDecision.SKIP);
        removed.setAddedOn(LocalDate.of(2026, 8, 1));
        assertThat(WatchlistSeedRunner.decide(removed)).isEqualTo(SeedDecision.SKIP);
    }
}
