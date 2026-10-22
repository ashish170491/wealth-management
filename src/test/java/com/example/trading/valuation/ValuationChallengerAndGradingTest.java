package com.example.trading.valuation;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.trading.valuation.ValuationFixtures.TODAY;
import static com.example.trading.valuation.ValuationFixtures.eligible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Three small guarantees that need no database: the AI challenger can only ask and is off by
 * default (SPEC §57.8), the grading rule VG-V1 is executable and counts a loss (SPEC §57.6), and the
 * manual capture endpoint is restricted and explicit (SPEC §57.7).
 */
class ValuationChallengerAndGradingTest {

    // ----------------------------------------------------------------------- the challenger

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatModel> noModel() {
        ObjectProvider<ChatModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @Test
    @DisplayName("off by default: a normal boot answers DISABLED and never asks for a model")
    void disabledByDefault() {
        ObjectProvider<ChatModel> p = noModel();
        var c = new ValuationAssumptionChallenger(p, new ValuationGapConfig());
        var r = c.challenge(ValuationGapService.assemble(eligible("NSE:GOOD")));
        assertThat(r.status()).isEqualTo(ValuationAssumptionChallenger.Status.DISABLED);
        assertThat(r.critiques()).isEmpty();
        verify(p, never()).getIfAvailable();
    }

    @Test
    @DisplayName("enabled but with no language model configured (spring.ai.model.chat=none) it is AI_NOT_CONFIGURED")
    void secondGate() {
        var config = new ValuationGapConfig();
        config.setChallengeEnabled(true);
        var c = new ValuationAssumptionChallenger(noModel(), config);
        assertThat(c.challenge(ValuationGapService.assemble(eligible("NSE:GOOD"))).status())
                .isEqualTo(ValuationAssumptionChallenger.Status.AI_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("a refused reading has nothing to question")
    void refusedReading() {
        var config = new ValuationGapConfig();
        config.setChallengeEnabled(true);
        @SuppressWarnings("unchecked") ObjectProvider<ChatModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(mock(ChatModel.class));
        var c = new ValuationAssumptionChallenger(p, config);
        var refused = ValuationGapService.assemble(new ValuationGapService.Inputs("NSE:N", "NSE:N", TODAY, null,
                eligible("NSE:N").annual(), List.of(), false));
        assertThat(c.challenge(refused).status()).isEqualTo(ValuationAssumptionChallenger.Status.READING_UNAVAILABLE);
        assertThat(c.challenge(null).status()).isEqualTo(ValuationAssumptionChallenger.Status.READING_UNAVAILABLE);
    }

    @Test
    @DisplayName("validation keeps a well-formed question and drops unknown targets, unknown kinds and empty rows")
    void validationShape() {
        String payload = "{\"growthPct\": 15.2, \"stdErrPp\": 1.1, \"fy\": 2031}";
        var rows = java.util.Arrays.asList(
                new AssumptionCritique("TREND", "QUESTIONABLE_ASSUMPTION", "The trend is 15.2 and its error is 1.1."),
                new AssumptionCritique("SECTOR_RANK", "MISSING_EVIDENCE", "No such target."),
                new AssumptionCritique("TREND", "OPINION", "No such kind."),
                new AssumptionCritique("TREND", "MISSING_EVIDENCE", " "),
                null);
        List<String> problems = new ArrayList<>();
        var kept = ValuationAssumptionChallenger.validate(rows, payload, problems);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).target()).isEqualTo("TREND");
        assertThat(problems).hasSize(4);
    }

    @Test
    @DisplayName("a number that is not in the input is rejected; rounding of a number that is, is allowed")
    void inventedNumbers() {
        String payload = "{\"growthPct\": 15.24, \"observations\": 10, \"fy\": 2031}";
        var made = new AssumptionCritique("TREND", "POSSIBLE_CONTRADICTION", "Growth of 22 percent is implied.");
        var rounded = new AssumptionCritique("TREND", "POSSIBLE_CONTRADICTION", "Growth is about 15.2 over 10 years.");
        List<String> problems = new ArrayList<>();
        var kept = ValuationAssumptionChallenger.validate(List.of(made, rounded), payload, problems);
        assertThat(kept).extracting(ValuationAssumptionChallenger.Critique::text).containsExactly(rounded.text());
        assertThat(problems).anyMatch(p -> p.contains("22"));
    }

    @Test
    @DisplayName("advice and prediction wording is rejected on word boundaries")
    void forbiddenWording() {
        String payload = "{\"x\": 1}";
        List<String> bad = List.of("You should sell here.", "Expected to rise.", "A forecast for 2031.",
                "This looks cheap.", "The target is wrong.", "It will rise.");
        for (String text : bad) {
            List<String> problems = new ArrayList<>();
            var kept = ValuationAssumptionChallenger.validate(
                    List.of(new AssumptionCritique("DATA", "MISSING_EVIDENCE", text)), payload, problems);
            assertThat(kept).as(text).isEmpty();
        }
        List<String> ok = new ArrayList<>();
        assertThat(ValuationAssumptionChallenger.validate(List.of(new AssumptionCritique("DATA", "MISSING_EVIDENCE",
                "Quarterly results are not on file for the latest year.")), payload, ok)).hasSize(1);
    }

    @Test
    @DisplayName("at most five questions survive")
    void atMostFive() {
        List<AssumptionCritique> many = new ArrayList<>();
        for (int i = 0; i < 9; i++) many.add(new AssumptionCritique("DATA", "MISSING_EVIDENCE", "Evidence " + i + " is missing."));
        var kept = ValuationAssumptionChallenger.validate(many, "{\"a\": 0, \"b\": 1, \"c\": 2, \"d\": 3, \"e\": 4, \"f\": 5, \"g\": 6, \"h\": 7, \"i\": 8}", new ArrayList<>());
        assertThat(kept).hasSize(5);
    }

    @Test
    @DisplayName("the challenger holds no repository: it cannot write, and a snapshot never stores AI text")
    void challengerCannotWrite() {
        for (var f : ValuationAssumptionChallenger.class.getDeclaredFields()) {
            assertThat(f.getType().getSimpleName()).doesNotContain("Repository").doesNotContain("Snapshot");
        }
        for (var f : ValuationGapSnapshotEntity.class.getDeclaredFields()) {
            assertThat(f.getName().toLowerCase()).doesNotContain("critique").doesNotContain("challenge");
        }
    }

    // ------------------------------------------------------------------------------ grading

    @Test
    @DisplayName("VG-V1: the graded value is normalised EPS x (1 + c)^(Y - base) from the stored slope alone")
    void gradedValue() {
        double slope = Math.log(1.15);
        assertThat(SnapshotGrading.gradedValue(40.0, slope, 2026, 2027)).isCloseTo(46.0, within(1e-9));
        assertThat(SnapshotGrading.gradedValue(40.0, slope, 2026, 2031)).isCloseTo(40.0 * Math.pow(1.15, 5), within(1e-9));
    }

    @Test
    @DisplayName("VG-V1: the score is the log ratio of the first-seen actual to the graded value, the same for both cohorts")
    void scoreIsTheLogRatio() {
        double slope = Math.log(1.10);
        var s = SnapshotGrading.score(50.0, slope, 2026, 2028, 66.0, LocalDate.of(2028, 5, 20));
        assertThat(s.outcome()).isEqualTo(SnapshotGrading.Outcome.GRADED);
        assertThat(s.gradedValue()).isCloseTo(50 * 1.21, within(1e-9));
        assertThat(s.lnError()).isCloseTo(Math.log(66.0 / (50 * 1.21)), within(1e-9));
    }

    @Test
    @DisplayName("VG-V1: a loss in the graded year is a COUNTED outcome, reported separately and never dropped")
    void nonPositiveActualIsCounted() {
        var s = SnapshotGrading.score(50.0, 0.1, 2026, 2027, -3.0, LocalDate.of(2027, 5, 20));
        assertThat(s.outcome()).isEqualTo(SnapshotGrading.Outcome.ACTUAL_NON_POSITIVE);
        assertThat(s.lnError()).isNull();
        assertThat(s.gradedValue()).isNotNull();
        assertThat(SnapshotGrading.score(50.0, 0.1, 2026, 2027, 0.0, LocalDate.of(2027, 5, 20)).outcome())
                .isEqualTo(SnapshotGrading.Outcome.ACTUAL_NON_POSITIVE);
    }

    @Test
    @DisplayName("VG-V1: an actual with no availability date, or no actual at all, is unmatured and not graded")
    void unmaturedActual() {
        assertThat(SnapshotGrading.score(50.0, 0.1, 2026, 2027, 60.0, null).outcome())
                .isEqualTo(SnapshotGrading.Outcome.UNMATURED);
        assertThat(SnapshotGrading.score(50.0, 0.1, 2026, 2027, null, LocalDate.of(2027, 5, 1)).outcome())
                .isEqualTo(SnapshotGrading.Outcome.UNMATURED);
        assertThat(SnapshotGrading.score(null, 0.1, 2026, 2027, 60.0, LocalDate.of(2027, 5, 1)).outcome())
                .isEqualTo(SnapshotGrading.Outcome.UNMATURED);
    }

    @Test
    @DisplayName("VG-V1: the unit is (symbol, base FY, ruleset) and the EARLIEST row written stands for it")
    void earliestRowStands() {
        var first = ValuationGapSnapshotEntity.builder().symbol("NSE:A").baseFiscalYear(2026).ruleset("VG-2")
                .trendLnSlope(0.1).createdAt(LocalDateTime.of(2026, 10, 14, 12, 35)).build();
        var revised = ValuationGapSnapshotEntity.builder().symbol("NSE:A").baseFiscalYear(2026).ruleset("VG-2")
                .trendLnSlope(0.2).createdAt(LocalDateTime.of(2026, 12, 1, 12, 35)).build();
        var nextYear = ValuationGapSnapshotEntity.builder().symbol("NSE:A").baseFiscalYear(2027).ruleset("VG-2")
                .trendLnSlope(0.3).createdAt(LocalDateTime.of(2027, 6, 1, 12, 35)).build();
        var unfitted = ValuationGapSnapshotEntity.builder().symbol("NSE:B").baseFiscalYear(2026).ruleset("VG-2")
                .createdAt(LocalDateTime.of(2026, 10, 14, 12, 35)).build();
        var units = SnapshotGrading.unitRows(List.of(revised, nextYear, first, unfitted));
        assertThat(units).containsExactlyInAnyOrder(first, nextYear);
        assertThat(units).doesNotContain(revised).doesNotContain(unfitted);
    }

    @Test
    @DisplayName("the follow-up that turns the record into a result is dated before the first filing can land")
    void deliverableDates() {
        assertThat(SnapshotGrading.VALIDATION_DELIVERABLE_DUE).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(SnapshotGrading.EARLIEST_GRADE_DATE).isEqualTo(LocalDate.of(2027, 4, 9));
        assertThat(SnapshotGrading.VALIDATION_DELIVERABLE_DUE).isBefore(SnapshotGrading.EARLIEST_GRADE_DATE);
    }

    // ------------------------------------------------------------------- the manual endpoint

    private static ValuationGapController controller(ValuationGapSnapshotService snapshots, ValuationGapConfig config) {
        var hours = mock(com.example.trading.scheduler.MarketHoursService.class);
        when(hours.getMarketZone()).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
        return new ValuationGapController(mock(ValuationGapService.class), snapshots,
                new ValuationAssumptionChallenger(noModel(), config), config, hours);
    }

    private static HttpServletRequest from(String addr) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRemoteAddr()).thenReturn(addr);
        return r;
    }

    @Test
    @DisplayName("POST /snapshot from a non-loopback address is 403 with the reason in the body, and captures nothing")
    void remoteCallerRefused() {
        var snapshots = mock(ValuationGapSnapshotService.class);
        var res = controller(snapshots, new ValuationGapConfig()).snapshot(from("192.168.1.20"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(((Map<?, ?>) res.getBody()).get("reason").toString()).contains("local");
        verify(snapshots, never()).captureNow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("POST /snapshot while switched off is 409 naming the property")
    void disabledIs409() {
        var snapshots = mock(ValuationGapSnapshotService.class);
        var config = new ValuationGapConfig();
        config.setSnapshotEnabled(false);
        var res = controller(snapshots, config).snapshot(from("127.0.0.1"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((Map<?, ?>) res.getBody()).get("reason").toString()).contains("valuation-gap.snapshot-enabled");
        verify(snapshots, never()).captureNow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("POST /snapshot from loopback runs the same capture, stamped MANUAL, and returns the explicit result")
    void loopbackRuns() {
        var snapshots = mock(ValuationGapSnapshotService.class);
        var result = new ValuationGapSnapshotService.CaptureResult(false, null, TODAY, 3, 3, 0, 0, Map.of());
        when(snapshots.captureNow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("MANUAL"))).thenReturn(result);
        for (String addr : new String[]{"127.0.0.1", "0:0:0:0:0:0:0:1", "::1"}) {
            ResponseEntity<?> res = controller(snapshots, new ValuationGapConfig()).snapshot(from(addr));
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isSameAs(result);
        }
    }

    @Test
    @DisplayName("POST /snapshot with no complete screening date is 409 with the skip reason, not a success")
    void skippedIs409() {
        var snapshots = mock(ValuationGapSnapshotService.class);
        when(snapshots.captureNow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ValuationGapSnapshotService.CaptureResult(true, "No complete screening run in the last 14 days.",
                        null, 0, 0, 0, 0, Map.of()));
        var res = controller(snapshots, new ValuationGapConfig()).snapshot(from("127.0.0.1"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((Map<?, ?>) res.getBody()).get("reason").toString()).contains("No complete screening run");
    }

    @Test
    @DisplayName("symbol input is validated: blank and odd characters are 400 with the reason; real symbols pass")
    void symbolValidation() {
        assertThat(ValuationGapController.invalidSymbol(null)).isNotNull();
        assertThat(ValuationGapController.invalidSymbol("  ")).isNotNull();
        assertThat(ValuationGapController.invalidSymbol("NSE:X; DROP TABLE")).isNotNull();
        assertThat(ValuationGapController.invalidSymbol("x".repeat(41))).isNotNull();
        for (String ok : new String[]{"NSE:RELIANCE", "NSE:M&M", "NSE:BAJAJ-AUTO", "BSE:ABB", "NSE:20MICRONS"}) {
            assertThat(ValuationGapController.invalidSymbol(ok)).as(ok).isNull();
        }
        var snapshots = mock(ValuationGapSnapshotService.class);
        var res = controller(snapshots, new ValuationGapConfig()).reading("<script>");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
