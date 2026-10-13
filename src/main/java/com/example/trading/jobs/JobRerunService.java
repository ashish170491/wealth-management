package com.example.trading.jobs;

import com.example.trading.broker.kite.TokenManagementService;
import com.example.trading.scheduler.MarketHoursService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Re-runs a scheduled job from the jobs page (SPEC §56.9).
 *
 * <p>Each re-run calls the job's {@link JobRerun} method - the same code its schedule runs, minus
 * the guards - on a single dedicated thread, never on the scheduler's pool. One thread means one
 * re-run at a time, so a re-run can neither take a pool thread from the afternoon jobs (B-014) nor
 * stack a second broker-heavy pass on top of the first (B-049). The run is filed in {@code job_run}
 * as {@code MANUAL} by the same recorder as a scheduled fire, so the page judges it the same way:
 * an ERROR line logged on the re-run thread makes it "finished with errors", not a success.
 */
@Service
@Slf4j
public class JobRerunService {

    /** One re-runnable job: the bean to call and its {@link JobRerun} method. */
    record Action(Object bean, Method method) {
    }

    /** What a refused request returns. The reason is shown to the investor verbatim. */
    public static class RerunRefused extends RuntimeException {
        public RerunRefused(String reason) {
            super(reason);
        }
    }

    public record Started(Long runId, String jobKey, String label, String note) {
    }

    private final ApplicationContext context;
    private final JobRunRecorder recorder;
    private final JobRunRepository runs;
    private final ObjectProvider<MarketHoursService> marketHours;
    private final ObjectProvider<TokenManagementService> tokens;

    private final AtomicInteger threadNo = new AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "job-rerun-" + threadNo.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /** Full job key -> action. Filled once the context is up. */
    private volatile Map<String, Action> actions = Map.of();
    /** Label of the re-run in progress, or null. Guarded by {@code this}. */
    private volatile String inProgress;

    public JobRerunService(ApplicationContext context, JobRunRecorder recorder, JobRunRepository runs,
                           ObjectProvider<MarketHoursService> marketHours,
                           ObjectProvider<TokenManagementService> tokens) {
        this.context = context;
        this.recorder = recorder;
        this.runs = runs;
        this.marketHours = marketHours;
        this.tokens = tokens;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void discover() {
        Map<String, Action> found = new HashMap<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) continue;
            Class<?> target = ClassUtils.getUserClass(type);
            if (!target.getName().startsWith("com.example.trading.")) continue;
            for (Method m : target.getDeclaredMethods()) {
                JobRerun ann = m.getAnnotation(JobRerun.class);
                if (ann == null) continue;
                if (!Modifier.isPublic(m.getModifiers()) || m.getParameterCount() != 0) {
                    log.warn("Job re-run: {}.{} is not a public no-argument method - ignored",
                            target.getSimpleName(), m.getName());
                    continue;
                }
                found.put(target.getName() + "." + ann.value(), new Action(context.getBean(name), m));
            }
        }
        actions = Map.copyOf(found);
        log.info("Job re-run: {} jobs can be re-run from the jobs page (SPEC §56.9)", found.size());
    }

    /** True when the job has a re-run method. */
    public boolean hasAction(String jobKey) {
        return actions.containsKey(jobKey);
    }

    /** The page's decision for one job, from the runs the overview already loaded. */
    public JobRerunPolicy.Decision decision(String jobKey, JobStatusDto.RunView lastRun,
                                            JobStatusDto.RunView lastReal) {
        return JobRerunPolicy.decide(inputs(jobKey,
                lastReal == null ? null : lastReal.outcome(),
                lastReal == null ? null : lastReal.startedAt(),
                lastRun != null && JobOutcome.RUNNING.name().equals(lastRun.outcome())));
    }

    /**
     * Starts a re-run, or throws {@link RerunRefused} carrying the reason. The decision is taken
     * again here rather than trusted from the page, because the page may be minutes old.
     */
    public synchronized Started start(String jobKey) {
        Action action = actions.get(jobKey);
        String label = JobLabels.of(jobKey).name();

        JobRunEntity latest = latestRow(jobKey, false).orElse(null);
        JobRunEntity latestReal = latestRow(jobKey, true).orElse(null);
        JobRerunPolicy.Decision d = JobRerunPolicy.decide(inputs(jobKey,
                latestReal == null ? null : latestReal.getOutcome(),
                latestReal == null ? null : latestReal.getStartedAt(),
                latest != null && JobOutcome.RUNNING.name().equals(latest.getOutcome())));
        if (d == null) {
            throw new RerunRefused("Its latest run did not go wrong, so there is nothing to re-run.");
        }
        if (!d.offered() || !d.allowedNow()) throw new RerunRefused(d.reason());

        Long id;
        try {
            id = recorder.openRow(jobKey, null, JobRunEntity.TRIGGER_MANUAL);
        } catch (Exception e) {
            // The point of running it from here is that the result is visible. A run nobody can
            // see the outcome of is what the per-job POST endpoints already offer.
            throw new RerunRefused("The run could not be recorded, so it was not started: " + e.getMessage());
        }

        inProgress = label;
        worker.submit(() -> run(jobKey, label, id, action));
        log.info("Job re-run: started {} ({}) as run {}", label, jobKey, id);
        return new Started(id, jobKey, label, "Started. This page follows it until it finishes.");
    }

    private void run(String jobKey, String label, Long id, Action action) {
        JobRunContext.begin(new JobRunContext.ActiveRun(id, jobKey, true));
        Throwable error = null;
        try {
            log.info("=== MANUAL RE-RUN: {} ===", label);
            action.method().invoke(action.bean());
        } catch (InvocationTargetException e) {
            error = e.getCause() != null ? e.getCause() : e;
            log.error("Manual re-run of {} failed: {}", label, error.toString(), error);
        } catch (Throwable t) {
            error = t;
            log.error("Manual re-run of {} could not be invoked: {}", label, t.toString(), t);
        } finally {
            recorder.settleCurrent(error);
            synchronized (this) {
                inProgress = null;
            }
        }
    }

    private JobRerunPolicy.Inputs inputs(String jobKey, String lastRealOutcome,
                                         LocalDateTime lastRealStartedAt, boolean runningNow) {
        JobLabels.Label label = JobLabels.of(jobKey);
        MarketHoursService mh = marketHours.getIfAvailable();
        TokenManagementService tm = tokens.getIfAvailable();
        boolean marketOpen = mh != null && mh.isMarketOpen();
        boolean session = tm != null && tm.hasValidToken();
        return new JobRerunPolicy.Inputs(JobLabels.shortKey(jobKey), hasAction(jobKey), label.touches(),
                lastRealOutcome, lastRealStartedAt, runningNow, inProgress,
                LocalDateTime.now(JobCatalog.IST), marketOpen, session);
    }

    private Optional<JobRunEntity> latestRow(String jobKey, boolean realOnly) {
        return (realOnly ? runs.findLatestRealRunPerJob() : runs.findLatestPerJob()).stream()
                .filter(r -> jobKey.equals(r.getJobKey()))
                .findFirst();
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
