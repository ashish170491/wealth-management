package com.example.trading.portfolio.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-061 — a classification row for a stock that is not currently held must never surface.
 *
 * <p>The snapshot table is keyed (symbol, classified_on), but a holding's symbol is <em>not stable
 * within a day</em>: the 15:18 broker sync re-prefixed every {@code NSE:INFY} as {@code BSE:INFY},
 * and classification runs either side of it wrote both under the same date. The dashboard then
 * showed 49 rows against 33 holdings, listing each re-prefixed stock twice.
 *
 * <p>The count was the visible symptom; the dangerous half is {@code effectiveTiers()}, which is
 * built from the same list and decides which holdings get core protection. A stale row must not be
 * able to grant that.
 */
class CoreStaleRowTest {

    private static CoreHoldingSnapshotEntity row(String symbol) {
        CoreHoldingSnapshotEntity e = new CoreHoldingSnapshotEntity();
        e.setSymbol(symbol);
        return e;
    }

    private static List<String> symbols(List<CoreHoldingSnapshotEntity> rows) {
        return rows.stream().map(CoreHoldingSnapshotEntity::getSymbol).toList();
    }

    @Test
    @DisplayName("A row whose symbol is not held is dropped")
    void staleRowIsDropped() {
        List<CoreHoldingSnapshotEntity> rows = List.of(row("BSE:INFY"), row("NSE:INFY"));
        assertThat(symbols(CoreClassificationService.retainActive(rows, Set.of("BSE:INFY"))))
                .containsExactly("BSE:INFY");
    }

    /**
     * The exact shape of the live defect: the same business under two exchange prefixes, both
     * classified on the same date. Only the one actually held may survive.
     */
    @Test
    @DisplayName("The re-prefixed duplicate is dropped, not merged or preferred")
    void reprefixedDuplicateIsDropped() {
        List<CoreHoldingSnapshotEntity> rows = List.of(
                row("NSE:INFY"), row("NSE:RELIANCE"), row("BSE:INFY"), row("BSE:RELIANCE"));
        List<String> kept = symbols(CoreClassificationService.retainActive(
                rows, Set.of("BSE:INFY", "BSE:RELIANCE")));
        assertThat(kept).containsExactly("BSE:INFY", "BSE:RELIANCE");
        assertThat(kept).doesNotContain("NSE:INFY", "NSE:RELIANCE");
    }

    @Test
    @DisplayName("Ordering of the surviving rows is preserved")
    void orderIsPreserved() {
        List<CoreHoldingSnapshotEntity> rows = List.of(row("A"), row("X"), row("B"), row("Y"));
        assertThat(symbols(CoreClassificationService.retainActive(rows, Set.of("A", "B"))))
                .containsExactly("A", "B");
    }

    @Test
    @DisplayName("An empty active set keeps nothing — it never falls back to showing everything")
    void emptyActiveKeepsNothing() {
        assertThat(CoreClassificationService.retainActive(List.of(row("A")), Set.of())).isEmpty();
    }

    @Test
    @DisplayName("Null rows and null symbols are survivable, not a crash")
    void nullsAreSurvivable() {
        assertThat(CoreClassificationService.retainActive(null, Set.of("A"))).isEmpty();
        List<CoreHoldingSnapshotEntity> withNulls = java.util.Arrays.asList(null, row(null), row("A"));
        assertThat(symbols(CoreClassificationService.retainActive(withNulls, Set.of("A"))))
                .containsExactly("A");
    }
}
