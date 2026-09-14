package com.example.trading.learning;

import com.example.trading.multibagger.MultibaggerScore;
import com.example.trading.persistence.ScreeningCoverageEntity;
import com.example.trading.persistence.ScreeningCoverageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Persists and reads the per-signal coverage vector (SPEC §38.2).
 *
 * <p>{@link ScreeningCoverage} does the arithmetic; this class does the I/O and nothing else.
 * It never throws into a screening run — a measurement of the run must not be able to break
 * the run — but a failure is logged at WARN naming what the absence will be mistaken for
 * (Gotcha 52), because a silently missing coverage row reads later as "that signal was fine
 * that week".
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScreeningCoverageService {

    /** Coverage at or below this is called out by name in the post-run log line. */
    private static final double POOR_COVERAGE_PERCENT = 50.0;

    private final ScreeningCoverageRepository repository;

    /**
     * Compute and persist the coverage vector for a completed screening run.
     *
     * @param screeningDate the run's date — same key {@code multibagger_scores} uses
     * @param scoringVersion provenance stamp for the run (SPEC §38.1)
     * @param universeSize   symbols the run set out to screen
     * @param qualityRejected symbols scored then dropped by the tier gate — a decision
     * @param failed          symbols that could not be scored at all — a blind spot
     * @param scores         the retained, scored stocks
     */
    public void capture(LocalDate screeningDate, String scoringVersion, int universeSize,
                        int qualityRejected, int failed, List<MultibaggerScore> scores) {
        if (scores == null || scores.isEmpty()) {
            log.warn("Coverage capture skipped for {}: the run produced no scores. No coverage rows "
                    + "will exist for this date, which later reads as 'not screened' rather than "
                    + "'screened and measured nothing' — check the run itself.", screeningDate);
            return;
        }
        try {
            List<ScreeningCoverage.SignalCoverage> vector = ScreeningCoverage.compute(scores);
            LocalDate date = screeningDate != null ? screeningDate : LocalDate.now();

            for (ScreeningCoverage.SignalCoverage c : vector) {
                // Upsert: a re-run on the same day replaces the earlier measurement, matching
                // how persistScores() treats the score rows it describes.
                repository.findByScreeningDateAndSignalName(date, c.signal())
                        .ifPresent(repository::delete);
                repository.save(ScreeningCoverageEntity.builder()
                        .screeningDate(date)
                        .signalName(c.signal())
                        .scoringVersion(scoringVersion)
                        .universeSize(universeSize)
                        .screenedCount(scores.size())
                        .qualityRejectedCount(qualityRejected)
                        .failedCount(failed)
                        .attempted(c.attempted())
                        .measured(c.measured())
                        .notApplicable(c.notApplicable())
                        .notMeasured(c.notMeasured())
                        .coveragePercent(c.coveragePercent())
                        .meanValue(c.mean())
                        .stdDev(c.stdDev())
                        .collapsed(c.collapsed())
                        .build());
            }

            logSummary(date, scoringVersion, universeSize, scores.size(), qualityRejected, failed, vector);
        } catch (Exception e) {
            log.warn("Coverage capture failed for {} ({}). The per-signal coverage vector for this "
                    + "run is missing, so a later IC reading for these signals cannot be told apart "
                    + "from a genuinely weak signal.", screeningDate, e.getMessage());
        }
    }

    /**
     * Human-readable summary in the run's own log. The endpoint is the detailed view; this
     * exists so a badly-covered signal is visible the day it happens without anyone going
     * looking — the failure mode both precedents shared was that nobody went looking.
     */
    private void logSummary(LocalDate date, String scoringVersion, int universeSize, int screened,
                            int qualityRejected, int failed,
                            List<ScreeningCoverage.SignalCoverage> vector) {
        String poor = vector.stream()
                .filter(c -> c.coveragePercent() != null && c.coveragePercent() <= POOR_COVERAGE_PERCENT)
                .sorted(Comparator.comparingDouble(ScreeningCoverage.SignalCoverage::coveragePercent))
                .map(c -> String.format("%s=%.0f%%", c.signal(), c.coveragePercent()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        String collapsed = vector.stream()
                .filter(c -> Boolean.TRUE.equals(c.collapsed()))
                .map(c -> String.format("%s(sd=%.1f)", c.signal(), c.stdDev()))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        log.info("Screening coverage [{} version={}]: {} of {} universe symbols retained "
                        + "({} tier-rejected, {} unscoreable); {} signals measured. "
                        + "Poorly covered (<= {}%): {}. Collapsed spread: {}.",
                date, scoringVersion, screened, universeSize, qualityRejected, failed,
                vector.size(), (int) POOR_COVERAGE_PERCENT, poor, collapsed);

        if (!"none".equals(collapsed)) {
            log.error("Screening coverage [{}]: signal(s) with collapsed cross-sectional spread: {}. "
                    + "A signal that cannot separate the universe contributes no information to the "
                    + "composite whatever its weight says — check its upstream data source before "
                    + "trusting this run's ranking.", date, collapsed);
        }
    }

    /** Coverage rows for the most recent run that has any. Empty list when none exist yet. */
    public List<ScreeningCoverageEntity> latest() {
        Optional<LocalDate> date = repository.findLatestDate();
        return date.map(repository::findByScreeningDateOrderBySignalNameAsc).orElseGet(List::of);
    }

    /** Coverage rows for one date. */
    public List<ScreeningCoverageEntity> forDate(LocalDate date) {
        return repository.findByScreeningDateOrderBySignalNameAsc(date);
    }

    /** One signal's coverage over time, oldest first. */
    public List<ScreeningCoverageEntity> trend(String signalName, int days) {
        int window = Math.max(1, days);
        return repository.findTrend(signalName, LocalDate.now().minusDays(window));
    }
}
