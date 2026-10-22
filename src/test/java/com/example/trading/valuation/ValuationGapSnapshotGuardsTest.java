package com.example.trading.valuation;

import com.example.trading.jobs.JobLabels;
import com.example.trading.jobs.JobRerun;
import com.example.trading.scheduler.MarketHoursService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SPEC §57.6: the snapshot is append-only and retained forever, enforced by tests rather than by
 * a comment - nothing can delete or bulk-save, nothing else reads the table, no cleanup arm touches
 * it - and its job is a weekday DB-only job with its own visible outcome.
 */
class ValuationGapSnapshotGuardsTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------------ immutability

    @Test
    @DisplayName("the repository can save and read but has no delete, no bulk save and no modifying query")
    void repositoryIsAppendOnly() {
        assertThat(org.springframework.data.repository.Repository.class
                .isAssignableFrom(ValuationGapSnapshotRepository.class)).isTrue();
        assertThat(org.springframework.data.jpa.repository.JpaRepository.class
                .isAssignableFrom(ValuationGapSnapshotRepository.class)).isFalse();
        assertThat(org.springframework.data.repository.CrudRepository.class
                .isAssignableFrom(ValuationGapSnapshotRepository.class)).isFalse();
        for (Method m : ValuationGapSnapshotRepository.class.getMethods()) {
            String n = m.getName().toLowerCase();
            assertThat(n).as(m.getName()).doesNotStartWith("delete").doesNotStartWith("remove")
                    .isNotEqualTo("saveall").isNotEqualTo("saveandflush").isNotEqualTo("flush");
            assertThat(m.isAnnotationPresent(Modifying.class)).as(m.getName()).isFalse();
        }
        assertThat(Set.of(ValuationGapSnapshotRepository.class.getMethods()).stream().map(Method::getName))
                .contains("save");
    }

    @Test
    @DisplayName("only the snapshot service, the mapper and the repository's own file mention the repository")
    void onlyTheSnapshotLayerTouchesTheRepository() throws IOException {
        Set<String> allowed = Set.of("ValuationGapSnapshotService.java", "ValuationGapSnapshotRepository.java");
        List<String> offenders = new ArrayList<>();
        for (Path p : sources()) {
            String name = p.getFileName().toString();
            if (allowed.contains(name)) continue;
            if (Files.readString(p).contains("ValuationGapSnapshotRepository")) offenders.add(p.toString());
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("nothing outside the snapshot layer refers to the entity either (the controller and challenger see none)")
    void onlyTheSnapshotLayerTouchesTheEntity() throws IOException {
        Set<String> allowed = Set.of("ValuationGapSnapshotService.java", "ValuationGapSnapshotRepository.java",
                "ValuationGapSnapshotEntity.java", "SnapshotMapper.java", "SnapshotGrading.java",
                "ValuationGapController.java");
        List<String> offenders = new ArrayList<>();
        for (Path p : sources()) {
            String name = p.getFileName().toString();
            if (allowed.contains(name)) continue;
            if (Files.readString(p).contains("ValuationGapSnapshotEntity")) offenders.add(p.toString());
        }
        assertThat(offenders).isEmpty();
    }

    // -------------------------------------------------------------------------- retention

    @Test
    @DisplayName("no cleanup class refers to the snapshot table, so retention is forever by construction")
    void noCleanupArm() throws IOException {
        for (Path p : sources()) {
            String name = p.getFileName().toString();
            if (!name.contains("Cleanup")) continue;
            String text = Files.readString(p);
            assertThat(text).as(name).doesNotContain("valuation_gap_snapshot")
                    .doesNotContain("ValuationGapSnapshot");
        }
    }

    // ----------------------------------------------------------------------------- scheduler

    @Test
    @DisplayName("the 12:35 weekday job is guarded by isMarketOpen first, calls a @JobRerun method, and is in JobLabels as DB-only")
    void schedulerShape() throws Exception {
        Method scheduled = ValuationGapSnapshotScheduler.class.getMethod("captureWeekdays");
        Scheduled ann = scheduled.getAnnotation(Scheduled.class);
        assertThat(ann.cron()).isEqualTo("0 35 12 * * MON-FRI");
        assertThat(ann.zone()).isEqualTo("Asia/Kolkata");
        Method rerun = ValuationGapSnapshotScheduler.class.getMethod("runNow");
        assertThat(rerun.getAnnotation(JobRerun.class).value()).isEqualTo("captureWeekdays");
        JobLabels.Label label = JobLabels.of("com.example.trading.valuation.ValuationGapSnapshotScheduler.captureWeekdays");
        assertThat(label.name()).isEqualTo("Valuation-gap validation snapshot");
        assertThat(label.touches()).isEqualTo("DB");          // so the re-run windows never treat it as broker work
        assertThat(label.touches().toLowerCase()).doesNotContain("kite").doesNotContain("nse");
    }

    @Test
    @DisplayName("outside market hours nothing runs and nothing is read")
    void guardedByMarketHours() {
        MarketHoursService hours = mock(MarketHoursService.class);
        ValuationGapSnapshotService snapshots = mock(ValuationGapSnapshotService.class);
        ValuationGapConfig config = new ValuationGapConfig();
        when(hours.isMarketOpen()).thenReturn(false);
        new ValuationGapSnapshotScheduler(hours, snapshots, config).captureWeekdays();
        verify(snapshots, never()).captureNow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("switched off in config the job does not capture, and says which property")
    void switchedOff() {
        MarketHoursService hours = mock(MarketHoursService.class);
        ValuationGapSnapshotService snapshots = mock(ValuationGapSnapshotService.class);
        ValuationGapConfig config = new ValuationGapConfig();
        config.setSnapshotEnabled(false);
        when(hours.isMarketOpen()).thenReturn(true);
        new ValuationGapSnapshotScheduler(hours, snapshots, config).captureWeekdays();
        verify(snapshots, never()).captureNow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the scheduler class contains the config property in its skip message and imports no broker type")
    void schedulerSource() throws IOException {
        String src = Files.readString(MAIN.resolve("com/example/trading/valuation/ValuationGapSnapshotScheduler.java"));
        assertThat(src).contains("valuation-gap.snapshot-enabled");
        assertThat(src.indexOf("isMarketOpen")).isLessThan(src.indexOf("isSnapshotEnabled"));
        assertThat(src).doesNotContain("broker.").doesNotContain("KiteBrokerClient").doesNotContain("hasValidToken");
    }

    @Test
    @DisplayName("the AI challenger is off by default, in code and in application.yml")
    void challengerDefaultsOff() throws IOException {
        assertThat(new ValuationGapConfig().isChallengeEnabled()).isFalse();
        assertThat(new ValuationGapConfig().isSnapshotEnabled()).isTrue();
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        int at = yml.indexOf("\nvaluation-gap:");
        assertThat(at).isPositive();
        String block = yml.substring(at, yml.indexOf("\nai:", at));
        assertThat(block).contains("challenge-enabled: false").contains("snapshot-enabled: true");
    }
}
