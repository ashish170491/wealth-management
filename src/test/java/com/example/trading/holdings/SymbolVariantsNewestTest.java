package com.example.trading.holdings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-150: a holding on BSE had ONE screening row under its own prefix, dated 2026-04-18, and the
 * daily NSE row was ignored because the lookup took the first spelling with any history. The
 * verdict then read a six-month-old score (ABCAPITAL: ACCUMULATE from a 53 while the NSE row said
 * HIGH_RISK). The newest reading wins; an exact spelling wins only a tie.
 */
class SymbolVariantsNewestTest {

    private record Row(String symbol, LocalDate date) {}

    private static List<Row> history(Map<String, List<Row>> db, String symbol) {
        return db.getOrDefault(symbol, List.of());
    }

    private static Row newest(String symbol, Map<String, List<Row>> db) {
        return SymbolVariants.<Row>newestAcrossSpellings(symbol, s -> history(db, s), r -> r.date());
    }

    @Test
    @DisplayName("A six-month-old one-off row under the held spelling loses to today's row under the other")
    void newestWinsOverExactSpelling() {
        Row stale = new Row("BSE:ABCAPITAL", LocalDate.of(2026, 4, 18));
        Row fresh = new Row("NSE:ABCAPITAL", LocalDate.of(2026, 10, 10));
        Map<String, List<Row>> db = Map.of("BSE:ABCAPITAL", List.of(stale), "NSE:ABCAPITAL", List.of(fresh));

        Row got = newest("BSE:ABCAPITAL", db);
        assertThat(got).isEqualTo(fresh);
    }

    @Test
    @DisplayName("On equal dates the exact spelling still wins (Gotcha 84's order is intact)")
    void exactSpellingWinsATie() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        Row bse = new Row("BSE:X", d);
        Row nse = new Row("NSE:X", d);
        Map<String, List<Row>> db = Map.of("BSE:X", List.of(bse), "NSE:X", List.of(nse));
        assertThat(newest("BSE:X", db)).isEqualTo(bse);
    }

    @Test
    @DisplayName("A spelling with no history is skipped; nothing anywhere is null, not an exception")
    void emptyAndMissing() {
        Row only = new Row("NSE:Y", LocalDate.of(2026, 9, 1));
        Map<String, List<Row>> db = Map.of("NSE:Y", List.of(only));
        assertThat(newest("BSE:Y", db)).isEqualTo(only);
        assertThat(newest("BSE:Z", db)).isNull();
    }
}
