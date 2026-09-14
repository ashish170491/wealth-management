package com.example.trading.holdings;

import com.example.trading.ai.AiService;
import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.portfolio.core.CoreClassificationService;
import com.example.trading.portfolio.core.CoreDto.CoreTier;
import com.example.trading.portfolio.core.CoreHoldingConfig;
import com.example.trading.portfolio.core.CoreOverlayService;
import com.example.trading.scheduler.MarketHoursService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The behavioural overlay and the dedup contract it depends on (SPEC §35.5, CLAUDE.md Gotcha 19).
 *
 * <p>This lives in {@code com.example.trading.holdings} rather than beside the rest of the core
 * tests because the thing under test is {@link ExitTimingAlertService}'s package-private dedup
 * behaviour — the exact behaviour that, left unguarded, silently emptied the 10:00 / 12:00 / 14:00
 * alert emails for weeks.
 *
 * <h2>The mode-specific assertion</h2>
 * "{@code sentAlertsToday} holds no key for a core holding" is true only in suppression mode. In
 * observation mode the alert really is sent, so it must dedup exactly as before or the 10:00 alert
 * would be re-sent at 12:00 and again at 14:00. Asserting the stronger statement in both modes
 * would pin a bug, not a rule.
 */
class CoreOverlayTest {

    private static final String SYMBOL = "NSE:ACME";

    private ExitTimingAlertService service(CoreOverlayService overlay) {
        return new ExitTimingAlertService(
                mock(HoldingsRepository.class),
                mock(EmailNotificationService.class),
                mock(EmailTemplateService.class),
                mock(AiService.class),
                overlay,
                mock(MarketHoursService.class));
    }

    /** A holding that trips RSI_OVERBOUGHT and NEAR_RESISTANCE, and nothing else. */
    private static HoldingsEntity overboughtWinner() {
        HoldingsEntity h = new HoldingsEntity();
        h.setSymbol(SYMBOL);
        h.setTradingSymbol("ACME");
        h.setCurrentPrice(100.0);
        h.setPnlPercent(35.0);
        h.setRsi14(78.0);
        h.setResistance1(101.0);          // within 1.5%
        h.setTrendDirection("BULLISH");
        h.setOverallScore(70);
        h.setRecommendation("SELL");
        return h;
    }

    private static CoreOverlayService overlay(boolean suppress, CoreTier tier) {
        CoreHoldingConfig config = new CoreHoldingConfig();
        config.setSuppressTechnicalExits(suppress);
        CoreClassificationService classification = mock(CoreClassificationService.class);
        when(classification.effectiveTiers()).thenReturn(Map.of(SYMBOL, tier));
        return new CoreOverlayService(config, classification);
    }

    // ------------------------------------------------------------------ dedup contract

    @Test
    @DisplayName("Observation mode: alerts fire AND claim their dedup slot, exactly as before")
    void observationModeDedupsNormally() {
        ExitTimingAlertService svc = service(overlay(false, CoreTier.CORE));
        HoldingsEntity h = overboughtWinner();

        List<?> first = svc.evaluateHolding(h, true);
        assertThat(first).isNotEmpty();
        assertThat(svc.alreadySentToday(SYMBOL, "RSI_OVERBOUGHT"))
                .as("the alert really was sent, so it must dedup or it repeats at 12:00 and 14:00")
                .isTrue();

        assertThat(svc.evaluateHolding(h, true))
                .as("second run of the same day is silent")
                .isEmpty();
    }

    @Test
    @DisplayName("Suppression mode: evaluating claims nothing, so no slot is burned on a withheld alert")
    void dedupFreeEvaluationClaimsNothing() {
        ExitTimingAlertService svc = service(overlay(true, CoreTier.CORE));
        HoldingsEntity h = overboughtWinner();

        List<?> first = svc.evaluateHolding(h, false);
        assertThat(first).isNotEmpty();
        assertThat(svc.alreadySentToday(SYMBOL, "RSI_OVERBOUGHT"))
                .as("a withheld alert must leave its slot free - otherwise flipping the flag off, "
                        + "or a same-day demotion, produces silence instead of the alert")
                .isFalse();

        assertThat(svc.evaluateHolding(h, false))
                .as("dedup-free evaluation reports the condition every time it is asked")
                .hasSameSizeAs(first);
    }

    @Test
    @DisplayName("markSent is the explicit claim the suppression path uses for what it does send")
    void markSentClaimsOnce() {
        ExitTimingAlertService svc = service(overlay(true, CoreTier.CORE));
        assertThat(svc.markSent(SYMBOL, "DEEP_LOSS_ACCELERATING")).isTrue();
        assertThat(svc.markSent(SYMBOL, "DEEP_LOSS_ACCELERATING")).isFalse();
    }

    // ------------------------------------------------------------------ overlay decisions

    @Test
    @DisplayName("Only the four price-driven alerts are technical — DEEP_LOSS is never suppressed")
    void deepLossIsNotTechnical() {
        assertThat(CoreOverlayService.isTechnicalAlert("RSI_OVERBOUGHT")).isTrue();
        assertThat(CoreOverlayService.isTechnicalAlert("NEAR_RESISTANCE")).isTrue();
        assertThat(CoreOverlayService.isTechnicalAlert("BROKE_SUPPORT")).isTrue();
        assertThat(CoreOverlayService.isTechnicalAlert("MOMENTUM_REVERSAL")).isTrue();
        assertThat(CoreOverlayService.isTechnicalAlert("DEEP_LOSS_ACCELERATING"))
                .as("a core holding down 15%+ is exactly when the thesis deserves a look")
                .isFalse();
    }

    @Test
    @DisplayName("R-1: suppression is off by default, so the shipped default changes no alert")
    void suppressionShipsOff() {
        assertThat(new CoreHoldingConfig().isSuppressTechnicalExits())
                .as("suppressing an exit alert removes a risk control; risk controls ship armed")
                .isFalse();
        assertThat(overlay(false, CoreTier.CORE).suppressionEnabled()).isFalse();
    }

    @Test
    @DisplayName("The display label is presentational and keeps the underlying signal visible")
    void displayLabelHidesNothing() {
        CoreOverlayService o = overlay(false, CoreTier.CORE);
        assertThat(o.displayRecommendation(SYMBOL, "SELL")).isEqualTo("HOLD_CORE (SELL)");
        assertThat(o.displayRecommendation(SYMBOL, "BOOK_PROFIT")).isEqualTo("HOLD_CORE (BOOK_PROFIT)");
        assertThat(o.displayRecommendation(SYMBOL, "BUY")).isEqualTo("BUY");
        assertThat(o.displayRecommendation("NSE:OTHER", "SELL"))
                .as("a stock that is not core is untouched")
                .isEqualTo("SELL");
    }

    @Test
    @DisplayName("CORE_WATCH is protected; SATELLITE and UNCLASSIFIED are not")
    void protectedTiers() {
        assertThat(overlay(false, CoreTier.CORE_WATCH).isProtected(SYMBOL)).isTrue();
        assertThat(overlay(false, CoreTier.SATELLITE).isProtected(SYMBOL)).isFalse();
        assertThat(overlay(false, CoreTier.UNCLASSIFIED).isProtected(SYMBOL)).isFalse();
    }

    @Test
    @DisplayName("With the feature disabled nothing is protected and nothing is looked up")
    void killSwitch() {
        CoreHoldingConfig config = new CoreHoldingConfig();
        config.setEnabled(false);
        CoreClassificationService classification = mock(CoreClassificationService.class);
        CoreOverlayService o = new CoreOverlayService(config, classification);

        assertThat(o.isProtected(SYMBOL)).isFalse();
        assertThat(o.suppressionEnabled()).isFalse();
        verify(classification, never()).effectiveTiers();
    }

    @Test
    @DisplayName("A failed tier lookup degrades to today's behaviour, never to withheld alerts")
    void lookupFailureLeavesEveryHoldingUnprotected() {
        CoreHoldingConfig config = new CoreHoldingConfig();
        CoreClassificationService classification = mock(CoreClassificationService.class);
        when(classification.effectiveTiers()).thenThrow(new IllegalStateException("db down"));

        CoreOverlayService o = new CoreOverlayService(config, classification);
        assertThat(o.isProtected(SYMBOL)).isFalse();
        assertThat(o.displayRecommendation(SYMBOL, "SELL")).isEqualTo("SELL");
    }

    @Test
    @DisplayName("Observed alerts are recorded for the flag decision, and a failure there is not fatal")
    void observedAlertsAreRecordedSafely() {
        CoreClassificationService classification = mock(CoreClassificationService.class);
        CoreOverlayService o = new CoreOverlayService(new CoreHoldingConfig(), classification);

        o.recordObservedAlerts(SYMBOL, List.of("RSI_OVERBOUGHT"));
        verify(classification).recordObservedAlerts(SYMBOL, List.of("RSI_OVERBOUGHT"));

        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(classification).recordObservedAlerts(anyString(), org.mockito.ArgumentMatchers.anyList());
        o.recordObservedAlerts(SYMBOL, List.of("NEAR_RESISTANCE"));   // must not propagate
    }
}
