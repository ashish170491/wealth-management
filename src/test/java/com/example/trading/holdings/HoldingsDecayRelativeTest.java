package com.example.trading.holdings;

import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-064: thesis decay is measured against the universe's own move, not against a fixed scale.
 *
 * <p>On 2026-08-27 ASIANPAINT read {@code DECAYING} (composite 85 -> 70) while the universe median
 * had fallen 74 -> 65.5 on the same dates — the engine's scale moved, the stock's rank did not.
 * The same reading fed the holdings email, the watchlist verdict and the core-holdings G5 gate.
 */
class HoldingsDecayRelativeTest {

    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate D30 = TODAY.minusDays(30);
    private static final LocalDate D60 = TODAY.minusDays(60);

    private final MultibaggerScoreRepository scores = mock(MultibaggerScoreRepository.class);
    private final HoldingsDecayService service =
            new HoldingsDecayService(mock(HoldingsRepository.class), scores);

    @Test
    @DisplayName("a stock that fell exactly as much as the universe is INTACT, and the reason says why")
    void universeWideFallIsNotDecay() {
        stubStock(85, 85, 70);                       // raw -15 over 30d
        stubUniverse(-15, 40);                       // every peer fell 15 too

        HoldingsDecayService.DecayAlert a = service.detectDecayForSymbol("NSE:X", null);

        assertThat(a.getDelta30d()).isEqualTo(-15);
        assertThat(a.getUniverseDelta30d()).isEqualTo(-15);
        assertThat(a.getRelativeDelta30d()).isZero();
        assertThat(a.getVerdict()).isEqualTo(HoldingsDecayService.Verdict.INTACT);
        assertThat(a.getReason()).contains("whole universe moved -15");
        // A+ -> B+ on the raw grade must not count as two grade steps either.
        assertThat(a.getGradeStepsDropped()).isZero();
    }

    @Test
    @DisplayName("a stock that fell while the universe held is still DECAYING")
    void idiosyncraticFallStillDecays() {
        stubStock(85, 85, 70);
        stubUniverse(0, 40);

        HoldingsDecayService.DecayAlert a = service.detectDecayForSymbol("NSE:X", null);

        assertThat(a.getRelativeDelta30d()).isEqualTo(-15);
        assertThat(a.getVerdict()).isEqualTo(HoldingsDecayService.Verdict.DECAYING);
        assertThat(a.getReason()).contains("vs peers");
    }

    @Test
    @DisplayName("a stock that fell less than a collapsing universe is a relative gainer")
    void relativeGainerIsIntact() {
        stubStock(80, 80, 72);                       // raw -8
        stubUniverse(-12, 40);

        HoldingsDecayService.DecayAlert a = service.detectDecayForSymbol("NSE:X", null);

        assertThat(a.getRelativeDelta30d()).isEqualTo(4);
        assertThat(a.getVerdict()).isEqualTo(HoldingsDecayService.Verdict.INTACT);
    }

    @Test
    @DisplayName("too few paired symbols: universe shift is null (never zero) and the raw move is used, saying so")
    void unmeasuredShiftFallsBackToRaw() {
        stubStock(85, 85, 70);
        stubUniverse(-15, HoldingsDecayService.MIN_PAIRED_SYMBOLS - 1);

        HoldingsDecayService.DecayAlert a = service.detectDecayForSymbol("NSE:X", null);

        assertThat(a.getUniverseDelta30d()).isNull();
        assertThat(a.getRelativeDelta30d()).isNull();
        assertThat(a.isUniverseShiftMeasured()).isFalse();
        assertThat(a.getVerdict()).isEqualTo(HoldingsDecayService.Verdict.DECAYING);
        assertThat(a.getReason()).contains("universe shift not measured");
    }

    @Test
    @DisplayName("universeShift pairs on symbol: stocks screened on only one date are ignored")
    void shiftIgnoresUnpairedRows() {
        List<MultibaggerScoreEntity> then = new ArrayList<>();
        List<MultibaggerScoreEntity> now = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            then.add(row("NSE:S" + i, D30, 60, "B"));
            now.add(row("NSE:S" + i, TODAY, 50, "C+"));
        }
        for (int i = 0; i < 100; i++) now.add(row("NSE:NEW" + i, TODAY, 95, "A+")); // newcomers, no prior row
        when(scores.findByScreeningDateOrderByCompositeScoreDesc(D30)).thenReturn(then);
        when(scores.findByScreeningDateOrderByCompositeScoreDesc(TODAY)).thenReturn(now);

        assertThat(service.universeShift(D30, TODAY)).isEqualTo(-10);
        assertThat(service.universeShift(TODAY, TODAY)).isZero();
        assertThat(service.universeShift(null, TODAY)).isNull();
    }

    // ---- fixtures ----

    /** Stock history: 60d-ago, 30d-ago, today. */
    private void stubStock(int at60, int at30, int now) {
        List<MultibaggerScoreEntity> history = List.of(
                row("NSE:X", D60, at60, HoldingsDecayService.gradeOf(at60)),
                row("NSE:X", D30, at30, HoldingsDecayService.gradeOf(at30)),
                row("NSE:X", TODAY, now, HoldingsDecayService.gradeOf(now)));
        when(scores.findTrend(eq("NSE:X"), any())).thenReturn(history);
    }

    /** {@code n} peers, each moving by {@code shift} between every pair of dates. */
    private void stubUniverse(int shift, int n) {
        List<MultibaggerScoreEntity> d60 = new ArrayList<>();
        List<MultibaggerScoreEntity> d30 = new ArrayList<>();
        List<MultibaggerScoreEntity> today = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int base = 40 + (i % 40);
            d60.add(row("NSE:P" + i, D60, base, "B"));
            d30.add(row("NSE:P" + i, D30, base, "B"));
            today.add(row("NSE:P" + i, TODAY, base + shift, "B"));
        }
        when(scores.findByScreeningDateOrderByCompositeScoreDesc(D60)).thenReturn(d60);
        when(scores.findByScreeningDateOrderByCompositeScoreDesc(D30)).thenReturn(d30);
        when(scores.findByScreeningDateOrderByCompositeScoreDesc(TODAY)).thenReturn(today);
    }

    private static MultibaggerScoreEntity row(String symbol, LocalDate date, int score, String grade) {
        MultibaggerScoreEntity e = new MultibaggerScoreEntity();
        e.setSymbol(symbol);
        e.setScreeningDate(date);
        e.setCompositeScore(score);
        e.setGrade(grade);
        return e;
    }
}
