package com.example.trading.jobs;

import com.example.trading.config.SchedulingConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The jobs page's read model (SPEC §56.7). DB-only plus the scheduler's in-memory registry: no
 * broker call, no NSE call, no email - safe on a page load (Gotcha 17, 39).
 *
 * <p>Composition only. Every rule lives in a pure class beside it - {@link JobSchedule} for what a
 * cron means, {@link JobExpectation} for whether a fire ran, {@link JobStats} for durations,
 * {@link JobVerdict} for the one-word answer - so this class decides nothing on its own.
 */
@Service
public class JobStatusService {

    /** A fire is judged only once the app has had this long to start it. */
    static final Duration GRACE = Duration.ofMinutes(2);
    public static final int DEFAULT_DAYS = 14;
    public static final int MAX_DAYS = 90;
    private static final int MAX_PROBLEMS = 200;

    private final JobCatalog catalog;
    private final JobRunRepository runs;
    private final AppBootRepository boots;
    private final JobBootTracker boot;
    private final ObjectProvider<TaskScheduler> scheduler;

    /** Schedules never change inside a process, and describing one walks a year of fires. */
    private final Map<String, JobSchedule> scheduleCache = new ConcurrentHashMap<>();

    public JobStatusService(JobCatalog catalog, JobRunRepository runs, AppBootRepository boots,
                            JobBootTracker boot, ObjectProvider<TaskScheduler> scheduler) {
        this.catalog = catalog;
        this.runs = runs;
        this.boots = boots;
        this.boot = boot;
        this.scheduler = scheduler;
    }

    public JobStatusDto.Overview overview(int days) {
        int window = clampDays(days);
        LocalDateTime now = LocalDateTime.now(JobCatalog.IST);
        LocalDateTime from = now.minusDays(window);
        LocalDateTime historyStart = boots.findFirstByOrderByStartedAtAsc()
                .map(AppBootEntity::getStartedAt).orElse(null);
        List<JobExpectation.Boot> alive = aliveBoots(from, now);

        // A little before the window, so a run that started just before it can still explain an
        // overlapped fire just inside it.
        Map<String, List<JobRunEntity>> byJob = new HashMap<>();
        for (JobRunEntity r : runs.findByStartedAtGreaterThanEqualOrderByStartedAtAsc(from.minusHours(12))) {
            byJob.computeIfAbsent(r.getJobKey(), k -> new ArrayList<>()).add(r);
        }
        Map<String, JobRunEntity> latest = newestPerJob(runs.findLatestPerJob());
        Map<String, JobRunEntity> latestReal = newestPerJob(runs.findLatestRealRunPerJob());
        Map<String, LocalDateTime> lastOk = new HashMap<>();
        for (Object[] row : runs.lastSucceededPerJob()) {
            lastOk.put((String) row[0], (LocalDateTime) row[1]);
        }

        List<JobStatusDto.JobRow> rows = new ArrayList<>();
        List<JobStatusDto.Problem> problems = new ArrayList<>();
        int failed = 0, errored = 0, interrupted = 0, missed = 0, overlapped = 0;

        for (JobCatalog.JobDefinition def : catalog.jobs()) {
            String key = def.key();
            JobLabels.Label label = JobLabels.of(key);
            JobSchedule schedule = scheduleCache.computeIfAbsent(def.cron(), JobCatalog::describe);
            List<JobRunEntity> jobRuns = byJob.getOrDefault(key, List.of());

            List<JobExpectation.Fire> fires = classifyFires(def.cron(), from, now, alive, jobRuns, historyStart);
            JobStatusDto.Counts counts = count(jobRuns, from, fires);
            JobStats stats = JobStats.of(jobRuns.stream()
                    .filter(r -> !r.getStartedAt().isBefore(from))
                    .map(r -> new JobStats.Sample(JobOutcome.parse(r.getOutcome()), r.getDurationMs(), r.getStartLagMs()))
                    .toList());

            JobStatusDto.RunView lastRun = view(latest.get(key));
            JobStatusDto.RunView lastReal = view(latestReal.get(key));
            JobVerdict verdict = JobVerdict.judge(lastRun, lastReal, counts, stats, window);

            rows.add(new JobStatusDto.JobRow(key, JobLabels.shortKey(key), label.name(), label.touches(),
                    def.cron(), schedule.text(), schedule.timesOnRunDay(), schedule.outsideWindowOnRunDay(),
                    schedule.runsPerYear(), def.nextRun(), lastRun, lastReal, lastOk.get(key), stats, counts,
                    verdict.verdict(), verdict.reasons()));

            failed += counts.failed();
            errored += counts.withErrors();
            interrupted += counts.interrupted();
            missed += counts.missed();
            overlapped += counts.overlapped();
            collectProblems(problems, key, label.name(), jobRuns, from, fires);
        }

        rows.sort(Comparator.comparingInt((JobStatusDto.JobRow r) -> verdictRank(r.verdict()))
                .thenComparing(r -> r.timesOnRunDay().isEmpty() ? LocalTime.MAX : r.timesOnRunDay().get(0))
                .thenComparing(JobStatusDto.JobRow::label));
        problems.sort(Comparator.comparing(JobStatusDto.Problem::at).reversed());
        List<JobStatusDto.Problem> capped = problems.size() > MAX_PROBLEMS ? problems.subList(0, MAX_PROBLEMS) : problems;

        List<JobStatusDto.RunView> runningNow = runs.findByOutcome(JobOutcome.RUNNING.name()).stream()
                .filter(r -> boot.bootId().equals(r.getBootId()))
                .sorted(Comparator.comparing(JobRunEntity::getStartedAt))
                .map(this::view)
                .toList();

        JobStatusDto.Summary summary = new JobStatusDto.Summary(rows.size(),
                countVerdict(rows, JobVerdict.PROBLEM), countVerdict(rows, JobVerdict.WATCH),
                countVerdict(rows, JobVerdict.OK),
                countVerdict(rows, JobVerdict.NO_HISTORY) + countVerdict(rows, JobVerdict.NOT_DUE),
                failed, errored, interrupted, missed, overlapped);

        return new JobStatusDto.Overview(now, window, historyStart, boot.startedAt(),
                SchedulingConfig.POOL_SIZE, threadsBusy(), summary, rows, runningNow,
                List.copyOf(capped), notRecorded(historyStart));
    }

    public Optional<JobStatusDto.History> history(String job, int days, int limit) {
        Optional<String> key = resolveKey(job);
        if (key.isEmpty()) return Optional.empty();
        int window = clampDays(days);
        LocalDateTime from = LocalDateTime.now(JobCatalog.IST).minusDays(window);
        List<JobRunEntity> list = runs.findByJobKeyAndStartedAtGreaterThanEqualOrderByStartedAtDesc(key.get(), from);
        JobStats stats = JobStats.of(list.stream()
                .map(r -> new JobStats.Sample(JobOutcome.parse(r.getOutcome()), r.getDurationMs(), r.getStartLagMs()))
                .toList());
        int cap = Math.max(1, Math.min(limit, 1000));
        String cron = catalog.cronFor(key.get()).orElse(null);
        String schedule = cron == null ? null : scheduleCache.computeIfAbsent(cron, JobCatalog::describe).text();
        return Optional.of(new JobStatusDto.History(key.get(), JobLabels.of(key.get()).name(), schedule, window,
                stats, list.stream().limit(cap).map(this::view).toList()));
    }

    public JobStatusDto.Timeline timeline(LocalDate date) {
        LocalDate day = date;
        if (day == null) {
            day = runs.findFirstByOrderByStartedAtDesc()
                    .map(r -> r.getStartedAt().toLocalDate())
                    .orElse(LocalDate.now(JobCatalog.IST));
        }
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        List<JobStatusDto.RunView> list = runs.findByStartedAtBetweenOrderByStartedAtAsc(start, end.minusNanos(1))
                .stream().map(this::view).toList();
        LocalDate prev = runs.findFirstByStartedAtLessThanOrderByStartedAtDesc(start)
                .map(r -> r.getStartedAt().toLocalDate()).orElse(null);
        LocalDate next = runs.findFirstByStartedAtGreaterThanEqualOrderByStartedAtAsc(end)
                .map(r -> r.getStartedAt().toLocalDate()).orElse(null);
        return new JobStatusDto.Timeline(day, prev, next, SchedulingConfig.POOL_SIZE, list);
    }

    // ------------------------------------------------------------------ helpers

    private List<JobExpectation.Fire> classifyFires(String cron, LocalDateTime from, LocalDateTime now,
                                                    List<JobExpectation.Boot> alive, List<JobRunEntity> jobRuns,
                                                    LocalDateTime historyStart) {
        if (historyStart == null) return List.of();
        LocalDateTime lower = from.isBefore(historyStart) ? historyStart.minusSeconds(1) : from;
        List<LocalDateTime> fires = JobSchedule.firesBetween(cron, JobCatalog.IST, lower, now.minus(GRACE));
        List<Boolean> outside = fires.stream().map(f -> JobSchedule.outsideWindows(f, JobCatalog.IST)).toList();
        List<JobExpectation.RunSpan> spans = jobRuns.stream()
                .map(r -> new JobExpectation.RunSpan(r.getScheduledFor(), r.getStartedAt(), r.getFinishedAt()))
                .toList();
        return JobExpectation.classify(fires, outside, alive, spans, historyStart, GRACE);
    }

    private static JobStatusDto.Counts count(List<JobRunEntity> jobRuns, LocalDateTime from,
                                             List<JobExpectation.Fire> fires) {
        int runsN = 0, ok = 0, errs = 0, failed = 0, skipped = 0, interrupted = 0, running = 0;
        for (JobRunEntity r : jobRuns) {
            if (r.getStartedAt().isBefore(from)) continue;
            runsN++;
            JobOutcome o = JobOutcome.parse(r.getOutcome());
            if (o == null) continue;
            switch (o) {
                case SUCCEEDED -> ok++;
                case COMPLETED_WITH_ERRORS -> errs++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case INTERRUPTED -> interrupted++;
                case RUNNING -> running++;
            }
        }
        int ran = 0, missed = 0, overlapped = 0, down = 0;
        for (JobExpectation.Fire f : fires) {
            switch (f.status()) {
                case RAN -> ran++;
                case MISSED -> missed++;
                case OVERLAPPED -> overlapped++;
                case APP_NOT_RUNNING -> down++;
            }
        }
        return new JobStatusDto.Counts(runsN, ok, errs, failed, skipped, interrupted, running,
                fires.size(), ran, missed, overlapped, down);
    }

    private void collectProblems(List<JobStatusDto.Problem> out, String key, String label,
                                 List<JobRunEntity> jobRuns, LocalDateTime from, List<JobExpectation.Fire> fires) {
        for (JobRunEntity r : jobRuns) {
            if (r.getStartedAt().isBefore(from)) continue;
            JobOutcome o = JobOutcome.parse(r.getOutcome());
            if (o == JobOutcome.FAILED) {
                out.add(new JobStatusDto.Problem(r.getStartedAt(), key, label, "FAILED",
                        "Threw out of the job", r.getReason()));
            } else if (o == JobOutcome.COMPLETED_WITH_ERRORS) {
                out.add(new JobStatusDto.Problem(r.getStartedAt(), key, label, "COMPLETED_WITH_ERRORS",
                        "Finished, but logged " + JobVerdict.plural(r.getErrorsLogged(), "error"), r.getFirstError()));
            } else if (o == JobOutcome.INTERRUPTED) {
                out.add(new JobStatusDto.Problem(r.getStartedAt(), key, label, "INTERRUPTED",
                        "Never finished", r.getReason()));
            }
        }
        for (JobExpectation.Fire f : fires) {
            if (f.status() == JobExpectation.Status.MISSED) {
                out.add(new JobStatusDto.Problem(f.at(), key, label, "MISSED", "Due, but did not run",
                        "The app was running and no earlier run of this job was still going, yet nothing ran for "
                                + "this fire. A dropped task or a scheduler fault - check the log around this time."));
            } else if (f.status() == JobExpectation.Status.OVERLAPPED) {
                out.add(new JobStatusDto.Problem(f.at(), key, label, "OVERLAPPED",
                        "Skipped - previous run still going",
                        "Spring times a cron's next fire from when the last run finished, so a run that outlasts "
                                + "its own interval silently swallows the fires it overlaps."));
            }
        }
    }

    private List<JobExpectation.Boot> aliveBoots(LocalDateTime from, LocalDateTime now) {
        List<JobExpectation.Boot> out = new ArrayList<>();
        for (AppBootEntity b : boots.findByLastSeenAtGreaterThanEqualOrderByStartedAtAsc(from.minusDays(1))) {
            // This process is alive by definition, whatever its throttled stamp says.
            LocalDateTime seen = boot.bootId().equals(b.getBootId()) ? now : b.getLastSeenAt();
            out.add(new JobExpectation.Boot(b.getStartedAt(), seen));
        }
        return out;
    }

    private static Map<String, JobRunEntity> newestPerJob(List<JobRunEntity> list) {
        Map<String, JobRunEntity> m = new HashMap<>();
        for (JobRunEntity r : list) {
            m.merge(r.getJobKey(), r, (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        return m;
    }

    private JobStatusDto.RunView view(JobRunEntity r) {
        if (r == null) return null;
        return new JobStatusDto.RunView(r.getId(), r.getJobKey(), JobLabels.of(r.getJobKey()).name(),
                r.getScheduledFor(), r.getStartedAt(), r.getFinishedAt(), r.getDurationMs(), r.getStartLagMs(),
                r.getOutcome(), r.getReason(), r.getErrorsLogged(), r.getWarningsLogged(), r.getFirstError(),
                r.getThreadName());
    }

    /** Accepts the full key or the short {@code Class.method} form. */
    Optional<String> resolveKey(String job) {
        if (job == null || job.isBlank()) return Optional.empty();
        String j = job.trim();
        for (JobCatalog.JobDefinition d : catalog.jobs()) {
            if (d.key().equals(j) || JobLabels.shortKey(d.key()).equals(j)) return Optional.of(d.key());
        }
        // A job deleted since: its history is still worth reading.
        return j.contains(".") && j.chars().filter(c -> c == '.').count() > 1 ? Optional.of(j) : Optional.empty();
    }

    private Integer threadsBusy() {
        TaskScheduler ts = scheduler.getIfAvailable();
        if (ts instanceof ThreadPoolTaskScheduler pool) {
            try {
                return pool.getActiveCount();
            } catch (IllegalStateException e) {
                return null;
            }
        }
        return null;
    }

    private static int clampDays(int days) {
        return days <= 0 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
    }

    private static int countVerdict(List<JobStatusDto.JobRow> rows, String v) {
        return (int) rows.stream().filter(r -> v.equals(r.verdict())).count();
    }

    private static int verdictRank(String v) {
        return switch (v) {
            case JobVerdict.PROBLEM -> 0;
            case JobVerdict.WATCH -> 1;
            case JobVerdict.NO_HISTORY -> 2;
            case JobVerdict.OK -> 3;
            default -> 4;
        };
    }

    static List<String> notRecorded(LocalDateTime historyStart) {
        List<String> out = new ArrayList<>();
        out.add(historyStart == null
                ? "No history yet: this ledger records from the first start of the app after it was added."
                : "History begins " + historyStart.toLocalDate() + ", when this ledger was added. Runs before that "
                + "were never recorded, so a fire before it is neither 'ran' nor 'missed' - it is simply not counted.");
        out.add("'Succeeded' means the job did not throw and logged no ERROR line. It does not mean it produced "
                + "anything: a screening that wrote zero rows reads Succeeded here. Whether the OUTPUT is fresh "
                + "and measured is what the Data Health screen checks.");
        out.add("Errors are counted only when logged on the job's own thread. Work a job hands to another thread "
                + "(a reactive web call, a parallel stream) logs elsewhere, so a run can read clean while a sub-task failed.");
        out.add("Manual triggers are not recorded. A POST such as /api/ipo/capture or /api/multibagger/screen does "
                + "the same work outside the scheduler, and so leaves no row here.");
        out.add("'Missed' is called only when the app was demonstrably running. It counts as running from its start "
                + "to the last time any job finished (or a clean shutdown), so a fire just after a silent crash "
                + "reads 'app not running' - this page errs toward under-reporting misses, never toward false ones.");
        out.add("There is no market-holiday calendar in this app. On a holiday the jobs still run if the app is "
                + "started, and their results reflect a closed market.");
        return out;
    }
}
