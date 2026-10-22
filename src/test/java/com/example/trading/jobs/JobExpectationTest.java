package com.example.trading.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how a cron fire with no run row is judged (SPEC §56.4).
 *
 * <p>The property that matters: a fire is called MISSED only when the app was demonstrably up for
 * it. This app is stopped and started by Windows tasks, so most fires without a row are "app not
 * running", and a page that called those missed would be red every morning and ignored by Friday.
 */
class JobExpectationTest {

    private static final Duration GRACE = Duration.ofMinutes(2);
    private static final LocalDateTime FIRE = LocalDateTime.of(2026, 10, 12, 14, 0);
    private static final LocalDateTime HISTORY = LocalDateTime.of(2026, 10, 1, 9, 0);

    private static JobExpectation.Boot boot(LocalDateTime start, LocalDateTime seen) {
        return new JobExpectation.Boot(start, seen);
    }

    private static JobExpectation.Status status(List<JobExpectation.Boot> boots, List<JobExpectation.RunSpan> runs) {
        return JobExpectation.classify(List.of(FIRE), List.of(false), boots, runs, HISTORY, GRACE).get(0).status();
    }

    private static final JobExpectation.Boot UP_ALL_DAY = boot(FIRE.withHour(9), FIRE.withHour(15).withMinute(30));

    @Test
    @DisplayName("A run filed against the fire is RAN, however late it started")
    void ran() {
        var run = new JobExpectation.RunSpan(FIRE, FIRE.plusMinutes(9), FIRE.plusMinutes(20));
        assertThat(status(List.of(UP_ALL_DAY), List.of(run))).isEqualTo(JobExpectation.Status.RAN);
    }

    @Test
    @DisplayName("No row and no app is APP_NOT_RUNNING, never MISSED")
    void appDown() {
        assertThat(status(List.of(), List.of())).isEqualTo(JobExpectation.Status.APP_NOT_RUNNING);
    }

    @Test
    @DisplayName("No row while the app was demonstrably up is MISSED")
    void missed() {
        assertThat(status(List.of(UP_ALL_DAY), List.of())).isEqualTo(JobExpectation.Status.MISSED);
    }

    @Test
    @DisplayName("An app last seen inside the grace after the fire may have died for it - not called missed")
    void diedAtTheFire() {
        assertThat(status(List.of(boot(FIRE.withHour(9), FIRE.plusMinutes(1))), List.of()))
                .isEqualTo(JobExpectation.Status.APP_NOT_RUNNING);
    }

    @Test
    @DisplayName("A fire that lands while the previous run is still going is OVERLAPPED, not MISSED")
    void overlapped() {
        var longRun = new JobExpectation.RunSpan(FIRE.minusMinutes(15), FIRE.minusMinutes(15), FIRE.plusMinutes(5));
        assertThat(status(List.of(UP_ALL_DAY), List.of(longRun))).isEqualTo(JobExpectation.Status.OVERLAPPED);
    }

    @Test
    @DisplayName("A run still going (no finish yet) also overlaps")
    void overlappedUnfinished() {
        var running = new JobExpectation.RunSpan(FIRE.minusMinutes(15), FIRE.minusMinutes(15), null);
        assertThat(status(List.of(UP_ALL_DAY), List.of(running))).isEqualTo(JobExpectation.Status.OVERLAPPED);
    }

    @Test
    @DisplayName("A run with no recorded fire still counts if it started inside the grace")
    void unattributedRunInsideGrace() {
        var run = new JobExpectation.RunSpan(null, FIRE.plusSeconds(30), FIRE.plusMinutes(1));
        assertThat(status(List.of(UP_ALL_DAY), List.of(run))).isEqualTo(JobExpectation.Status.RAN);
    }

    @Test
    @DisplayName("Fires before the ledger began are not judged at all")
    void beforeHistoryDropped() {
        LocalDateTime old = HISTORY.minusDays(1);
        assertThat(JobExpectation.classify(List.of(old), List.of(false), List.of(), List.of(), HISTORY, GRACE)).isEmpty();
        assertThat(JobExpectation.classify(List.of(FIRE), List.of(false), List.of(), List.of(), null, GRACE)).isEmpty();
    }

    @Test
    @DisplayName("The outside-window flag rides through untouched")
    void outsideFlag() {
        var fires = JobExpectation.classify(List.of(FIRE), List.of(true), List.of(UP_ALL_DAY), List.of(), HISTORY, GRACE);
        assertThat(fires.get(0).outsideWindow()).isTrue();
    }

    // ------------------------------------------------------------------ B-184: a job is judged from when it existed

    private static final LocalDateTime BOOT_NOW = LocalDateTime.of(2026, 10, 22, 8, 30);

    @Test
    @DisplayName("B-184: fires before a job's first run are not judged - the live case, a job added on 14 Oct")
    void aNewJobIsNotJudgedOnFiresBeforeItExisted() {
        // The app was up on 12 and 13 Oct at 12:35; the job did not exist until its first (manual) run on 14 Oct 00:47.
        LocalDateTime fire12 = LocalDateTime.of(2026, 10, 12, 12, 35);
        LocalDateTime fire13 = LocalDateTime.of(2026, 10, 13, 12, 35);
        LocalDateTime fire14 = LocalDateTime.of(2026, 10, 14, 12, 35);
        var up = boot(LocalDateTime.of(2026, 10, 12, 9, 0), LocalDateTime.of(2026, 10, 14, 15, 30));
        var run14 = new JobExpectation.RunSpan(fire14, fire14.plusSeconds(2), fire14.plusSeconds(5));
        LocalDateTime firstRun = LocalDateTime.of(2026, 10, 14, 0, 47);

        LocalDateTime from = JobExpectation.judgedFrom(HISTORY, firstRun, BOOT_NOW, GRACE);
        var judged = JobExpectation.classify(List.of(fire12, fire13, fire14), List.of(false, false, false),
                List.of(up), List.of(run14), from, GRACE);
        assertThat(judged).extracting(JobExpectation.Fire::status).containsExactly(JobExpectation.Status.RAN);

        // The old rule (judge from the first boot of the app) calls the same two fires MISSED. This is the case
        // that tells the two rules apart, so reverting the fix fails here.
        var old = JobExpectation.classify(List.of(fire12, fire13, fire14), List.of(false, false, false),
                List.of(up), List.of(run14), HISTORY, GRACE);
        assertThat(old).extracting(JobExpectation.Fire::status)
                .containsExactly(JobExpectation.Status.MISSED, JobExpectation.Status.MISSED, JobExpectation.Status.RAN);
    }

    @Test
    @DisplayName("B-184: an established job that stops running is still MISSED - the fix must not hide a real failure")
    void anEstablishedJobStillMissesFires() {
        LocalDateTime firstRun = HISTORY.plusDays(1); // ran long before the window
        LocalDateTime from = JobExpectation.judgedFrom(HISTORY, firstRun, BOOT_NOW, GRACE);
        assertThat(from).isBefore(FIRE);
        assertThat(JobExpectation.classify(List.of(FIRE), List.of(false), List.of(UP_ALL_DAY), List.of(), from, GRACE)
                .get(0).status()).isEqualTo(JobExpectation.Status.MISSED);
    }

    @Test
    @DisplayName("B-184: a job that has never run is judged from the boot now serving it, not from the app's first boot")
    void aJobWithNoRunIsJudgedFromTheCurrentBoot() {
        assertThat(JobExpectation.judgedFrom(HISTORY, null, BOOT_NOW, GRACE)).isEqualTo(BOOT_NOW);
    }

    @Test
    @DisplayName("B-184: the start is never earlier than the first boot on record, and unknown inputs fall back safely")
    void judgedFromBounds() {
        assertThat(JobExpectation.judgedFrom(HISTORY, HISTORY.minusDays(5), BOOT_NOW, GRACE)).isEqualTo(HISTORY);
        assertThat(JobExpectation.judgedFrom(HISTORY, null, null, GRACE)).isEqualTo(HISTORY);
        assertThat(JobExpectation.judgedFrom(null, LocalDateTime.of(2026, 10, 14, 0, 47), BOOT_NOW, GRACE)).isNull();
    }
}
