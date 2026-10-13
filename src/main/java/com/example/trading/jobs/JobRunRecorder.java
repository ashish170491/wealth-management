package com.example.trading.jobs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Writes one {@link JobRunEntity} per scheduled run (SPEC §56.2).
 *
 * <p>Hooked into Spring's own scheduled-task observation (registered in {@code SchedulingConfig}),
 * not into each job: Spring 6.2 wraps every {@code @Scheduled} method in an observation that opens
 * on the job's thread immediately before the method and closes immediately after it. So every job
 * - including any added later - is recorded with no edit to the job, and nothing here can be
 * forgotten the way a per-job call could.
 *
 * <p>The row is inserted at start, then settled at stop. Nothing in here may break a job: every
 * database call is wrapped, and a failed write leaves the run un-recorded rather than failed.
 */
@Component
@Slf4j
public class JobRunRecorder implements ObservationHandler<ScheduledTaskObservationContext> {

    /** How far back to look for the fire a run answered - longer than any plausible pool queue. */
    static final Duration FIRE_LOOKBACK = Duration.ofHours(6);

    private final JobRunRepository runs;
    private final JobCatalog catalog;
    private final JobBootTracker boot;

    public JobRunRecorder(JobRunRepository runs, JobCatalog catalog, JobBootTracker boot) {
        this.runs = runs;
        this.catalog = catalog;
        this.boot = boot;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ScheduledTaskObservationContext;
    }

    @Override
    public void onStart(ScheduledTaskObservationContext context) {
        String key = keyOf(context.getMethod());
        Long id = null;
        try {
            LocalDateTime now = LocalDateTime.now(JobCatalog.IST);
            LocalDateTime fire = catalog.cronFor(key)
                    .map(cron -> JobSchedule.latestFireAtOrBefore(cron, JobCatalog.IST, now, FIRE_LOOKBACK))
                    .orElse(null);
            id = openRow(key, fire, null);
        } catch (Exception e) {
            log.debug("Job ledger: could not record the start of {}: {}", key, e.getMessage());
        }
        // Begin counting even when the insert failed: the counters cost nothing, and the stop
        // handler simply has no row to update.
        JobRunContext.begin(new JobRunContext.ActiveRun(id, key));
    }

    @Override
    public void onError(ScheduledTaskObservationContext context) {
        JobRunContext.ActiveRun run = JobRunContext.current();
        if (run != null) run.thrown = context.getError();
    }

    @Override
    public void onStop(ScheduledTaskObservationContext context) {
        settleCurrent(context.getError());
    }

    /**
     * Inserts the RUNNING row for a run and returns its id. Shared by scheduled fires and by
     * re-runs from the jobs page (SPEC §56.9), so the two are recorded by one piece of code.
     * {@code fire} is null for a manual run: it answers no cron fire, and filing it against one
     * would make a fire that never ran read as RAN.
     */
    Long openRow(String key, LocalDateTime fire, String trigger) {
        boot.ensureRegistered();
        LocalDateTime now = LocalDateTime.now(JobCatalog.IST);
        JobRunEntity row = runs.save(JobRunEntity.builder()
                .jobKey(key)
                .bootId(boot.bootId())
                .scheduledFor(fire)
                .startedAt(now)
                .startLagMs(fire == null ? null : Duration.between(fire, now).toMillis())
                .outcome(JobOutcome.RUNNING.name())
                .threadName(JobOutcome.truncate(Thread.currentThread().getName(), 40))
                .runTrigger(trigger)
                .build());
        return row.getId();
    }

    /**
     * Ends the run active on this thread and settles its row. {@code error} is what escaped the
     * method, if anything; an error recorded earlier by {@link #onError} wins.
     */
    void settleCurrent(Throwable error) {
        JobRunContext.ActiveRun run = JobRunContext.current();
        // Clear before writing, so a WARN from this method's own failure is not counted against the job.
        JobRunContext.end();
        if (run == null || run.runId == null) return;
        try {
            JobRunEntity row = runs.findById(run.runId).orElse(null);
            if (row == null) return;
            Throwable thrown = run.thrown != null ? run.thrown : error;
            JobOutcome.Signals signals = new JobOutcome.Signals(thrown, run.errorsLogged,
                    run.explicitSkipReason, run.firstWindowCheckOpen);
            LocalDateTime now = LocalDateTime.now(JobCatalog.IST);
            row.setFinishedAt(now);
            row.setDurationMs(Duration.between(row.getStartedAt(), now).toMillis());
            row.setOutcome(signals.outcome().name());
            row.setReason(signals.reason());
            row.setErrorsLogged(run.errorsLogged);
            row.setWarningsLogged(run.warningsLogged);
            row.setFirstError(run.firstError);
            // A manual run is inserted on the request thread and runs on the re-run thread; record
            // the thread that did the work.
            row.setThreadName(JobOutcome.truncate(Thread.currentThread().getName(), 40));
            runs.save(row);
            boot.touch();
        } catch (Exception e) {
            log.debug("Job ledger: could not record the end of {}: {}", run.jobKey, e.getMessage());
        }
    }

    /** Must match {@link JobCatalog#keyOf}: declaring class name + "." + method name. */
    static String keyOf(Method method) {
        if (method == null) return "unknown";
        return JobCatalog.stripProxy(method.getDeclaringClass().getName() + "." + method.getName());
    }
}
