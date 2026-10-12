package com.example.trading.jobs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every {@code @Scheduled} job the running application actually registered (SPEC §56.5).
 *
 * <p>Read from Spring's {@link ScheduledTaskHolder} rather than from a hand-kept table, for two
 * reasons. A hand-kept list drifts - CLAUDE.md and SPEC §15 have each carried the wrong job count
 * at least once (B-076) - while this one cannot: a job that is registered is listed, and a job that
 * is deleted disappears. And the cron here is the <b>resolved</b> expression, with every
 * {@code ${...}} placeholder already substituted from application.yml, which is the trap Data
 * Health fell into by reading the annotation literal (its javadoc on {@code SCHEDULE} records it).
 *
 * <p>Every cron in this app is declared with {@code zone = "Asia/Kolkata"}; CronTask does not
 * expose its zone, so IST is assumed here and the next-run time is taken from the scheduler itself
 * ({@link ScheduledTask#nextExecution()}), which knows the real zone.
 */
@Component
public class JobCatalog {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ObjectProvider<ScheduledTaskHolder> holders;

    /** jobKey -> resolved cron. Fixed for the life of the process once the scheduler is up. */
    private volatile Map<String, String> cronByKey = Map.of();

    public JobCatalog(ObjectProvider<ScheduledTaskHolder> holders) {
        this.holders = holders;
    }

    /** A registered job as the scheduler sees it right now. */
    public record JobDefinition(String key, String cron, LocalDateTime nextRun) {
    }

    public List<JobDefinition> jobs() {
        Map<String, JobDefinition> byKey = new LinkedHashMap<>();
        holders.orderedStream().forEach(holder -> {
            for (ScheduledTask st : holder.getScheduledTasks()) {
                Task task = st.getTask();
                if (!(task instanceof CronTask cron)) continue;   // this app has no fixed-rate jobs
                String key = keyOf(task);
                if (key == null || byKey.containsKey(key)) continue;
                byKey.put(key, new JobDefinition(key, cron.getExpression(), nextRun(st.nextExecution())));
            }
        });
        List<JobDefinition> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparing(JobDefinition::key));
        if (!out.isEmpty() && cronByKey.isEmpty()) {
            Map<String, String> m = new LinkedHashMap<>();
            out.forEach(d -> m.put(d.key(), d.cron()));
            cronByKey = Map.copyOf(m);
        }
        return out;
    }

    /** The resolved cron of a job, or empty when the scheduler has not registered it (yet). */
    public Optional<String> cronFor(String jobKey) {
        if (cronByKey.isEmpty()) jobs();
        return Optional.ofNullable(cronByKey.get(jobKey));
    }

    /**
     * The scheduler's next execution, rounded to the second. It is derived from the pending
     * future's remaining delay, so it reads a fraction early - "07:59:59.999" for an 08:00 job.
     */
    static LocalDateTime nextRun(Instant next) {
        if (next == null) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(Math.round(next.toEpochMilli() / 1000.0)), IST);
    }

    /** Schedule facts for one cron, relative to now. */
    public static JobSchedule describe(String cron) {
        return JobSchedule.describe(cron, IST, ZonedDateTime.now(IST));
    }

    /**
     * The job key for a registered task. Spring wraps the method runnable twice, and the outer
     * wrapper's {@code toString()} delegates to {@code ScheduledMethodRunnable.toString()}, which is
     * {@code declaringClass.getName() + "." + method.getName()} - exactly what {@link JobRunRecorder}
     * builds from the observation context, so the two sides key identically.
     */
    static String keyOf(Task task) {
        String s = String.valueOf(task);
        return s.isBlank() ? null : stripProxy(s);
    }

    /** {@code Foo$$SpringCGLIB$$0.bar} -> {@code Foo.bar}: a proxied bean is the same job. */
    static String stripProxy(String key) {
        int cg = key.indexOf("$$");
        if (cg < 0) return key;
        int dot = key.lastIndexOf('.');
        return dot > cg ? key.substring(0, cg) + key.substring(dot) : key.substring(0, cg);
    }
}
