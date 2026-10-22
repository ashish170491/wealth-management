package com.example.trading.valuation;

import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.example.trading.valuation.ValuationFixtures.TODAY;
import static com.example.trading.valuation.ValuationFixtures.eligible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * SPEC §57.6: capture is idempotent, only a complete and recent screening date is captured, one
 * failing stock cannot lose the others, a refusal is data, and nothing here can reach a broker.
 */
class ValuationGapSnapshotServiceTest {

    private ValuationGapService reader;
    private ValuationGapSnapshotRepository repo;
    private ValuationGapSnapshotService service;

    @BeforeEach
    void setUp() {
        reader = mock(ValuationGapService.class);
        repo = mock(ValuationGapSnapshotRepository.class);
        service = new ValuationGapSnapshotService(reader, repo);
        when(reader.latestCompleteDate(TODAY)).thenReturn(java.util.Optional.of(TODAY));
        when(repo.findStoredHashes(anyString())).thenReturn(List.of());
    }

    private ValuationReading good(String symbol) {
        return ValuationGapService.assemble(eligible(symbol));
    }

    private ValuationReading refusedLoss(String symbol) {
        var base = eligible(symbol);
        List<AnnualFundamentalsEntity> annual = new ArrayList<>(base.annual());
        annual.get(3).setNetProfit(-5.0);
        return ValuationGapService.assemble(ValuationFixtures.withAnnual(base, annual, base.quarters()));
    }

    private void readings(ValuationReading... rs) {
        when(reader.readAll(TODAY, TODAY)).thenReturn(new ValuationGapService.Batch(List.of(rs), 0));
    }

    @Test
    @DisplayName("a first capture writes one row per stock, refusals included, and reports refusals by reason")
    void firstCaptureWritesEverything() {
        readings(good("NSE:A"), good("NSE:B"), refusedLoss("NSE:C"));
        var result = service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        assertThat(result.skipped()).isFalse();
        assertThat(result.evaluated()).isEqualTo(3);
        assertThat(result.written()).isEqualTo(3);
        assertThat(result.unchanged()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.refused()).containsEntry("LOSS_IN_RECORD", 1);
        verify(repo, times(3)).save(any());
    }

    @Test
    @DisplayName("running it again over unchanged inputs writes nothing (pressing the manual button twice is safe)")
    void secondCaptureIsIdempotent() {
        var a = good("NSE:A");
        var b = refusedLoss("NSE:B");
        String ha = SnapshotMapper.inputsHash(a);
        String hb = SnapshotMapper.inputsHash(b);
        when(repo.findStoredHashes(anyString())).thenReturn(List.of(
                new Object[]{"NSE:A", ha}, new Object[]{"NSE:B", hb}));
        readings(a, b);
        var result = service.captureNow(TODAY, ValuationGapSnapshotService.MANUAL);
        assertThat(result.written()).isZero();
        assertThat(result.unchanged()).isEqualTo(2);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("a revision (a restated figure) appends a NEW row beside the old one and never edits it")
    void revisionAppends() {
        var before = good("NSE:A");
        var revisedInputs = eligible("NSE:A");
        revisedInputs.annual().get(9).setNetProfit(revisedInputs.annual().get(9).getNetProfit() * 1.02);
        var after = ValuationGapService.assemble(revisedInputs);
        when(repo.findStoredHashes(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"NSE:A", SnapshotMapper.inputsHash(before)}));
        readings(after);
        var result = service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        assertThat(result.written()).isEqualTo(1);
        verify(repo, times(1)).save(any());
    }

    @Test
    @DisplayName("a loss year that appears later is captured in the next row, with the loss year in the series")
    void lossYearIsFrozenInTheNextRow() {
        readings(refusedLoss("NSE:A"));
        service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        ArgumentCaptor<ValuationGapSnapshotEntity> saved = ArgumentCaptor.forClass(ValuationGapSnapshotEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(saved.getValue().getEpsSeriesJson()).contains("-5.0");
    }

    @Test
    @DisplayName("no complete screening date in the window is a skip with the reason, and nothing is read")
    void noCompleteDate() {
        when(reader.latestCompleteDate(TODAY)).thenReturn(java.util.Optional.empty());
        var result = service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        assertThat(result.skipped()).isTrue();
        assertThat(result.skipReason()).contains("No complete screening run");
        verify(reader, never()).readAll(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("a complete date more than 5 days old is skipped, not recorded as if current")
    void staleCompleteDate() {
        when(reader.latestCompleteDate(TODAY)).thenReturn(java.util.Optional.of(TODAY.minusDays(6)));
        var result = service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        assertThat(result.skipped()).isTrue();
        assertThat(result.skipReason()).contains("more than 5 days");
        verify(repo, never()).save(any());

        when(reader.latestCompleteDate(TODAY)).thenReturn(java.util.Optional.of(TODAY.minusDays(5)));
        when(reader.readAll(TODAY.minusDays(5), TODAY)).thenReturn(new ValuationGapService.Batch(List.of(), 0));
        assertThat(service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED).skipped()).isFalse();
    }

    @Test
    @DisplayName("a partial screening date is never asked for: only the latest COMPLETE date reaches readAll")
    void onlyTheCompleteDateIsRead() {
        LocalDate complete = TODAY.minusDays(2);
        when(reader.latestCompleteDate(TODAY)).thenReturn(java.util.Optional.of(complete));
        when(reader.readAll(complete, TODAY)).thenReturn(new ValuationGapService.Batch(List.of(good("NSE:A")), 0));
        service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        verify(reader).readAll(complete, TODAY);
        verify(reader, never()).readAll(TODAY, TODAY);
    }

    @Test
    @DisplayName("one stock that cannot be saved is counted as failed and the rest are still written")
    void oneFailureDoesNotLoseTheOthers() {
        readings(good("NSE:A"), good("NSE:B"), good("NSE:C"));
        when(repo.save(any())).thenAnswer(inv -> {
            ValuationGapSnapshotEntity e = inv.getArgument(0);
            if (e.getSymbol().equals("NSE:B")) throw new IllegalStateException("column too narrow");
            return e;
        });
        var result = service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        assertThat(result.written()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        verify(repo, times(3)).save(any());      // all three were tried: no saveAll, no early exit
    }

    @Test
    @DisplayName("a unique-key collision means the record is already on file: unchanged, not an error")
    void collisionIsUnchanged() {
        readings(good("NSE:A"));
        doThrow(new DataIntegrityViolationException("uk_vgap_snapshot_symbol_ruleset_inputs"))
                .when(repo).save(any());
        var result = service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("readings that failed upstream are counted in the failed total")
    void upstreamFailuresAreCounted() {
        when(reader.readAll(TODAY, TODAY)).thenReturn(new ValuationGapService.Batch(List.of(good("NSE:A")), 2));
        var result = service.captureNow(TODAY, ValuationGapSnapshotService.SCHEDULED);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.evaluated()).isEqualTo(3);
    }

    @Test
    @DisplayName("the capture origin is stamped on the row and is the only difference between scheduled and manual")
    void originIsStamped() {
        readings(good("NSE:A"));
        service.captureNow(TODAY, ValuationGapSnapshotService.MANUAL);
        ArgumentCaptor<ValuationGapSnapshotEntity> saved = ArgumentCaptor.forClass(ValuationGapSnapshotEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getCaptureOrigin()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("capture talks to the lens and the snapshot table only: no broker, NSE or model collaborator exists to call")
    void noBrokerCollaborator() {
        for (var f : ValuationGapSnapshotService.class.getDeclaredFields()) {
            String n = f.getType().getSimpleName().toLowerCase();
            assertThat(n).doesNotContain("kite").doesNotContain("nse").doesNotContain("broker")
                    .doesNotContain("chatmodel").doesNotContain("webclient");
        }
        assertThat(ValuationGapService.class.getDeclaredFields())
                .noneMatch(f -> f.getType().getSimpleName().toLowerCase().matches(".*(kite|nsedata|broker|chatmodel|webclient).*"));
    }

    @Test
    @DisplayName("the summary for a stock with no rows has a zero count and no timestamp")
    void summaryEmpty() {
        when(repo.countBySymbol("NSE:A")).thenReturn(0L);
        var s = service.summary("NSE:A");
        assertThat(s.count()).isZero();
        assertThat(s.latestCreatedAt()).isNull();
        verify(repo, never()).findLatestCreatedAtForSymbol(anyString());
        verifyNoMoreInteractions(reader);
    }

    @Test
    @DisplayName("coverage says plainly that collecting rows is not validation")
    void coverageBlockHonesty() {
        when(repo.count()).thenReturn(0L);
        when(repo.countByStatus()).thenReturn(List.of());
        when(repo.countByRuleset()).thenReturn(List.of());
        var block = service.coverageBlock();
        assertThat(block.get("validationStatus")).isEqualTo("NO_MATURED_RESULTS");
        assertThat(String.valueOf(block.get("note"))).contains("not validation");
        assertThat(block.get("validationDeliverableDue")).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(block.get("earliestGradeDate")).isEqualTo(LocalDate.of(2027, 4, 9));
    }
}
