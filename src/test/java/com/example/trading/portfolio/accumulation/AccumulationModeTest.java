package com.example.trading.portfolio.accumulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the accumulation-mode contract (SPEC §8.2, §19; B-077).
 *
 * <p>The rule this defends: an accumulation plan may schedule a purchase by <em>date</em> or by
 * <em>price</em>, but never by <em>signal</em>. A tranche fired by a signal is a buy signal
 * wearing a plan's clothes, and SPEC §19 rules those out for this platform. The mode was worse
 * than merely unwanted — it was accepted, persisted, and then never evaluated by the reminder
 * service, so an investor who created one waited indefinitely for a tranche that could not fire.
 *
 * <p>{@code validate()} runs before any repository is touched, so these cases construct the
 * service with null repositories on purpose: reaching a repository here would itself be a defect.
 */
class AccumulationModeTest {

    private final AccumulationService service = new AccumulationService(null, null);

    private AccumulationDto.CreatePlanRequest request(String mode, String signalName) {
        return new AccumulationDto.CreatePlanRequest(
                "NSE:RELIANCE", 60000.0, 3, mode,
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 12, 7),
                1400.0, 3.0, signalName, "test");
    }

    @Test
    @DisplayName("SIGNAL_GATED is refused, and the refusal says why and what to use instead")
    void signalGatedIsRefused() {
        assertThatThrownBy(() -> service.createPlan(request(AccumulationService.MODE_SIGNAL_GATED, "BREAKOUT")))
                .isInstanceOf(AccumulationService.UnsupportedModeException.class)
                .hasMessageContaining("SIGNAL_GATED")
                .hasMessageContaining("§19")
                .hasMessageContaining(AccumulationService.MODE_SIP)
                .hasMessageContaining(AccumulationService.MODE_PRICE_LADDER);
    }

    @Test
    @DisplayName("A well-formed SIGNAL_GATED request is refused too — it is the mode, not the payload")
    void signalGatedIsRefusedEvenWhenComplete() {
        // Before B-077 this exact request was accepted and stored. Supplying a valid signalName
        // must not buy a way past the refusal.
        assertThatThrownBy(() -> service.createPlan(request(AccumulationService.MODE_SIGNAL_GATED, "SECTOR_REVERSAL")))
                .isInstanceOf(AccumulationService.UnsupportedModeException.class);
    }

    @Test
    @DisplayName("An unknown mode stays a plain bad request, not a policy refusal")
    void unknownModeIsNotAPolicyRefusal() {
        // The two must not collapse: one means "you asked for something we removed on purpose"
        // (422 with a reason), the other means "you sent nonsense" (400).
        assertThatThrownBy(() -> service.createPlan(request("TELEPATHY", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(AccumulationService.UnsupportedModeException.class);
    }

    @Test
    @DisplayName("The two supported modes still validate")
    void supportedModesStillValidate() {
        // Null repositories mean these fail at persistence, not validation — which is the point:
        // the refusal above happens earlier than any of this.
        assertThatThrownBy(() -> service.createPlan(request(AccumulationService.MODE_SIP, null)))
                .isNotInstanceOf(AccumulationService.UnsupportedModeException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createPlan(request(AccumulationService.MODE_PRICE_LADDER, null)))
                .isNotInstanceOf(AccumulationService.UnsupportedModeException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The constant survives, so legacy rows still read back")
    void constantIsRetainedForLegacyRows() {
        // SPEC §8.2: retained in the data model, never triggered. Deleting the constant would
        // break reading a row written before the refusal shipped.
        assertThat(AccumulationService.MODE_SIGNAL_GATED).isEqualTo("SIGNAL_GATED");
    }
}
