package com.example.trading.jobs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives real Logback through the tap (SPEC §56.3).
 *
 * <p>This is the path that turns the codebase's universal {@code catch (Exception e) { log.error }}
 * into a visible outcome, and it cannot be exercised by a live run on demand - no job fails on
 * cue. So it is pinned here: an ERROR logged on the job's thread is counted and quoted, a line on
 * any other thread is not, and nothing is counted when no run is active.
 */
class JobLogTapTest {

    private final JobLogTap tap = new JobLogTap();
    private final org.slf4j.Logger log = LoggerFactory.getLogger("com.example.trading.fake.FakeScheduler");

    @AfterEach
    void detach() {
        JobRunContext.end();
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(JobLogTap.APPENDER_NAME);
    }

    @Test
    @DisplayName("A caught-and-logged failure on the job's thread is counted and quoted")
    void countsOwnThread() {
        tap.install();
        JobRunContext.ActiveRun run = new JobRunContext.ActiveRun(1L, "k");
        JobRunContext.begin(run);
        log.warn("slow response");
        log.error("Daily screening failed: {}", "timeout", new IllegalStateException("x"));
        log.error("second");
        assertThat(run.errorsLogged).isEqualTo(2);
        assertThat(run.warningsLogged).isEqualTo(1);
        assertThat(run.firstError).isEqualTo("[FakeScheduler] Daily screening failed: timeout (IllegalStateException)");
        assertThat(new JobOutcome.Signals(null, run.errorsLogged, null, true).outcome())
                .isEqualTo(JobOutcome.COMPLETED_WITH_ERRORS);
    }

    @Test
    @DisplayName("Another thread's ERROR is not attributed to the run")
    void ignoresOtherThreads() throws Exception {
        tap.install();
        JobRunContext.ActiveRun run = new JobRunContext.ActiveRun(1L, "k");
        JobRunContext.begin(run);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = new Thread(() -> log.error("from a reactive callback"));
        t.setUncaughtExceptionHandler((th, e) -> err.set(e));
        t.start();
        t.join();
        assertThat(err.get()).isNull();
        assertThat(run.errorsLogged).isZero();
    }

    @Test
    @DisplayName("Installing twice attaches one appender, and no run means nothing is counted")
    void idempotentAndInert() {
        tap.install();
        new JobLogTap().install();
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(root.getAppender(JobLogTap.APPENDER_NAME)).isSameAs(tap);
        log.error("no run active");   // must not throw, must not count anywhere
        assertThat(JobRunContext.current()).isNull();
    }
}
