package com.example.trading.valuation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes and reads the method-validation record (SPEC §57.6).
 *
 * <p><b>DB-only and token-free.</b> Nothing here calls Kite, NSE or a model, so the weekday job that
 * runs it is decoupled from the broker session and from the Saturday window (Gotcha 28 is not
 * engaged: this is not a third Saturday caller).
 *
 * <p><b>Not {@code @Transactional}, on purpose.</b> Each row is saved on its own and commits on its
 * own (Gotcha 129): a bulk save is a single point of total failure, and here one malformed stock
 * must not discard three hundred good rows. A failed row is counted and logged at ERROR, which marks
 * <i>this job's own</i> {@code job_run} as completed-with-errors - an alarm that is no longer drowned
 * out by the routinely noisy Saturday run (SPEC §56).
 *
 * <p><b>A row is written only when the method's inputs change</b> ({@link SnapshotMapper#inputsHash}),
 * so re-running is idempotent: a second pass over unchanged inputs writes nothing, which is also what
 * makes the manual {@code POST /snapshot} safe to press twice. A manual capture goes through exactly
 * the same eligibility and data-quality gates as the scheduled one - it calls the same method and
 * differs only in the {@code capture_origin} it stamps.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValuationGapSnapshotService {

    private final ValuationGapService valuationGapService;
    private final ValuationGapSnapshotRepository repository;

    public static final String SCHEDULED = "SCHEDULED";
    public static final String MANUAL = "MANUAL";

    /**
     * @param skipped    true when nothing was attempted; {@code skipReason} then says why
     * @param written    rows appended
     * @param unchanged  stocks whose inputs were already on record
     * @param failed     stocks that could not be read or whose row could not be saved
     * @param refused    refusal rows newly written, by reason. A refusal row is data, not an error.
     */
    public record CaptureResult(boolean skipped, String skipReason, LocalDate screeningDate, int evaluated,
                                int written, int unchanged, int failed, Map<String, Integer> refused) {
        static CaptureResult skip(String why) {
            return new CaptureResult(true, why, null, 0, 0, 0, 0, Map.of());
        }
    }

    /**
     * Capture the latest complete screening date.
     *
     * @param asOf   today, IST
     * @param origin {@link #SCHEDULED} or {@link #MANUAL}
     */
    public CaptureResult captureNow(LocalDate asOf, String origin) {
        LocalDate complete = valuationGapService.latestCompleteDate(asOf).orElse(null);
        if (complete == null) {
            return CaptureResult.skip("No complete screening run in the last 14 days.");
        }
        if (ChronoUnit.DAYS.between(complete, asOf) > ValuationGapRules.MAX_CAPTURE_DATE_AGE_DAYS) {
            return CaptureResult.skip("The latest complete screening run is from " + complete + ", more than "
                    + ValuationGapRules.MAX_CAPTURE_DATE_AGE_DAYS + " days ago, so no snapshot was taken (a stale "
                    + "price date would be recorded as if it were current).");
        }

        ValuationGapService.Batch batch = valuationGapService.readAll(complete, asOf);
        Set<String> stored = new HashSet<>();
        for (Object[] row : repository.findStoredHashes(ValuationGapRules.RULESET)) {
            stored.add(row[0] + "|" + row[1]);
        }

        int written = 0, unchanged = 0, failed = batch.failed();
        Map<String, Integer> refused = new LinkedHashMap<>();
        for (ValuationReading reading : batch.readings()) {
            try {
                ValuationGapSnapshotEntity entity = SnapshotMapper.toEntity(reading, origin);
                if (entity == null) continue;
                if (stored.contains(entity.getSymbol() + "|" + entity.getInputsHash())) {
                    unchanged++;
                    continue;
                }
                repository.save(entity);
                stored.add(entity.getSymbol() + "|" + entity.getInputsHash());
                written++;
                if (reading.status() == ValuationReading.Status.UNAVAILABLE) {
                    refused.merge(reading.reasonCode().name(), 1, Integer::sum);
                    log.info("Valuation-gap snapshot: {} recorded as unavailable ({})", entity.getSymbol(),
                            reading.reasonCode());
                }
            } catch (DataIntegrityViolationException collision) {
                // The unique key (symbol, ruleset, inputs hash) says this exact record is already on
                // file - written by an earlier pass between the lookup above and this save.
                unchanged++;
            } catch (Exception e) {
                failed++;
                log.error("Valuation-gap snapshot: could not save {}: {}", reading.symbol(), e.toString());
            }
        }

        CaptureResult result = new CaptureResult(false, null, complete, batch.readings().size() + batch.failed(),
                written, unchanged, failed, refused);
        log.info("Valuation-gap snapshot ({}): screening date {}, {} evaluated, {} written, {} unchanged, "
                        + "{} failed, refusals written {}",
                origin, complete, result.evaluated(), written, unchanged, failed, refused);
        if (failed > 0) {
            // ERROR, not WARN: this is what the jobs page counts to read the run as completed-with-errors.
            log.error("Valuation-gap snapshot: {} stock(s) failed and were NOT recorded. Rerun is safe: "
                    + "rows already on file are skipped.", failed);
        }
        return result;
    }

    // ------------------------------------------------------------------------------------ reads

    /** Stored rows for one stock, newest first. */
    public List<ValuationGapSnapshotEntity> forSymbol(String symbol) {
        return repository.findBySymbolOrderByCreatedAtDesc(symbol);
    }

    public ValuationReading.Snapshots summary(String symbol) {
        long count = repository.countBySymbol(symbol);
        return new ValuationReading.Snapshots(count, count == 0 ? null : repository.findLatestCreatedAtForSymbol(symbol));
    }

    /** The snapshot half of {@code GET /coverage}. */
    public Map<String, Object> coverageBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", repository.count());
        LocalDateTime latest = repository.findLatestCreatedAt();
        m.put("latestRowCreatedAt", latest);
        m.put("earliestRowCreatedAt", repository.findEarliestCreatedAt());
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] r : repository.countByStatus()) byStatus.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        m.put("byStatus", byStatus);
        Map<String, Long> byRuleset = new LinkedHashMap<>();
        for (Object[] r : repository.countByRuleset()) byRuleset.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        m.put("byRuleset", byRuleset);
        m.put("cadence", "A row is written only when a stock's inputs change: a new fiscal-year filing, a "
                + "restatement, history added by a backfill, a changed status or a rule-set change. Most days "
                + "write nothing.");
        m.put("earliestGradeDate", SnapshotGrading.EARLIEST_GRADE_DATE);
        m.put("validationDeliverableDue", SnapshotGrading.VALIDATION_DELIVERABLE_DUE);
        m.put("validationStatus", "NO_MATURED_RESULTS");
        m.put("note", "Collecting these rows is not validation. Until filed FY2027 results exist the record "
                + "demonstrates nothing about whether a past earnings trend carries on, and even once it "
                + "does, at most five graded years exist, so only descriptive statements will ever be "
                + "supportable.");
        return m;
    }
}
