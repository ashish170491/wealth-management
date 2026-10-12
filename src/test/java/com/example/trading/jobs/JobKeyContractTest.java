package com.example.trading.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two sides of the ledger must name a job identically (SPEC §56.5).
 *
 * <p>The recorder builds its key from the method the observation hands it; the catalog builds it
 * from the registered task's {@code toString()}. If those ever disagree - a Spring upgrade changes
 * a wrapper's toString, say - every run is filed under a key the page never looks up, and every
 * job reads "no history" while the table fills. Nothing would throw. This test is the alarm.
 */
class JobKeyContractTest {

    public static class SampleJob {
        public void runIt() {
        }
    }

    @Test
    @DisplayName("Recorder key == catalog key, through Spring's real wrappers")
    void recorderAndCatalogAgree() throws Exception {
        SampleJob target = new SampleJob();
        Method m = SampleJob.class.getMethod("runIt");
        CronTask task = new CronTask(new ScheduledMethodRunnable(target, m), "0 0 14 * * MON-FRI");
        assertThat(JobCatalog.keyOf(task)).isEqualTo(JobRunRecorder.keyOf(m));
        assertThat(JobRunRecorder.keyOf(m)).endsWith("JobKeyContractTest$SampleJob.runIt");
    }

    @Test
    @DisplayName("A CGLIB-proxied bean is the same job")
    void proxyStripped() {
        assertThat(JobCatalog.stripProxy("com.x.Foo$$SpringCGLIB$$0.bar")).isEqualTo("com.x.Foo.bar");
        assertThat(JobCatalog.stripProxy("com.x.Foo.bar")).isEqualTo("com.x.Foo.bar");
        assertThat(JobLabels.shortKey("com.example.trading.holdings.HoldingsScheduler.analyzeHoldings"))
                .isEqualTo("HoldingsScheduler.analyzeHoldings");
    }

    @Test
    @DisplayName("Every @Scheduled method has a readable label, and every label names a real job")
    void labelsMatchTheCode() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Set<String> scheduled = new TreeSet<>();
        for (BeanDefinition bd : scan.findCandidateComponents("com.example.trading")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Scheduled.class)) {
                    scheduled.add(JobLabels.shortKey(JobRunRecorder.keyOf(m)));
                }
            }
        }
        assertThat(scheduled).as("found the scheduled jobs at all").hasSizeGreaterThan(20);
        Set<String> unlabelled = new TreeSet<>();
        for (String k : scheduled) {
            if (JobLabels.of("x." + k).touches() == null) unlabelled.add(k);
        }
        assertThat(unlabelled).as("add these to JobLabels so the page names them in words").isEmpty();
        assertThat(JobLabels.size()).as("a label for a job that no longer exists").isEqualTo(scheduled.size());
    }

    @Test
    @DisplayName("An unlabelled job still gets a readable name, never a blank")
    void fallbackName() {
        assertThat(JobLabels.of("com.x.NewScheduler.pullSomethingDaily").name())
                .isEqualTo("Pull something daily (NewScheduler)");
    }
}
