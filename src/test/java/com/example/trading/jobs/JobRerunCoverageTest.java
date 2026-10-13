package com.example.trading.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §56.9: every scheduled job either has a {@link JobRerun} method or a stated reason in
 * {@link JobRerunPolicy#NOT_RERUNNABLE} for having none - so a new job cannot silently arrive
 * with a re-run button that does nothing, or with no explanation for the missing one.
 */
class JobRerunCoverageTest {

    record Found(Set<String> scheduled, Set<String> rerunTargets, List<String> malformed) {
    }

    private static Found scan() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        Set<String> scheduled = new HashSet<>();
        Set<String> targets = new HashSet<>();
        List<String> malformed = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.example.trading")) {
            Class<?> cls = Class.forName(bd.getBeanClassName());
            Set<String> scheduledHere = new HashSet<>();
            for (Method m : cls.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Scheduled.class)) {
                    scheduledHere.add(m.getName());
                    scheduled.add(cls.getSimpleName() + "." + m.getName());
                }
            }
            for (Method m : cls.getDeclaredMethods()) {
                JobRerun ann = m.getAnnotation(JobRerun.class);
                if (ann == null) continue;
                String key = cls.getSimpleName() + "." + ann.value();
                targets.add(key);
                if (!scheduledHere.contains(ann.value())) malformed.add(key + " names no @Scheduled method in its class");
                if (!Modifier.isPublic(m.getModifiers())) malformed.add(key + " re-run method is not public");
                if (m.getParameterCount() != 0) malformed.add(key + " re-run method takes arguments");
                if (m.isAnnotationPresent(Scheduled.class)) malformed.add(key + " re-run method is itself scheduled");
            }
        }
        return new Found(scheduled, targets, malformed);
    }

    @Test
    @DisplayName("Every scheduled job has a re-run method or a written reason for having none")
    void everyJobIsCovered() throws Exception {
        Found f = scan();
        assertThat(f.scheduled()).hasSizeGreaterThanOrEqualTo(30);
        Set<String> uncovered = new HashSet<>(f.scheduled());
        uncovered.removeAll(f.rerunTargets());
        uncovered.removeAll(JobRerunPolicy.NOT_RERUNNABLE.keySet());
        assertThat(uncovered).as("scheduled jobs with neither @JobRerun nor a NOT_RERUNNABLE reason").isEmpty();
    }

    @Test
    @DisplayName("A re-run method is public, takes nothing, is not itself scheduled, and names a real job")
    void rerunMethodsAreWellFormed() throws Exception {
        assertThat(scan().malformed()).isEmpty();
    }

    @Test
    @DisplayName("No job is both re-runnable and listed as not re-runnable")
    void noContradiction() throws Exception {
        Set<String> both = new HashSet<>(scan().rerunTargets());
        both.retainAll(JobRerunPolicy.NOT_RERUNNABLE.keySet());
        assertThat(both).isEmpty();
    }

    @Test
    @DisplayName("Every NOT_RERUNNABLE entry names a job that exists")
    void exclusionsNameRealJobs() throws Exception {
        assertThat(scan().scheduled()).containsAll(JobRerunPolicy.NOT_RERUNNABLE.keySet());
    }
}
