package com.example.trading.jobs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Counts the WARN and ERROR lines a scheduled job logs while it runs (SPEC §56.3).
 *
 * <p>This is the half of the ledger that does the real work. Almost every scheduled method here
 * ends in {@code catch (Exception e) { log.error(...) }}, so a job that failed entirely still
 * returns normally - "did it throw?" reads green on nearly every real failure. The IPO capture
 * died every day for a week on a column width (B-116) and the NAV pull on a CHECK constraint
 * (B-135); in both the scheduler caught the exception and carried on, and both were found from a
 * freshness stamp that had stopped moving. Counting the ERROR lines on the job's own thread turns
 * that pattern into an outcome: COMPLETED_WITH_ERRORS, with the first message beside it.
 *
 * <p>Unsynchronised on purpose: it touches only the calling thread's own run, and the synchronised
 * base class would put one lock in front of every log line the app writes.
 *
 * <p>Attributed by thread, so a line logged by work the job handed to another thread - a reactive
 * WebClient callback, a parallel stream - is not counted. The page says so.
 */
@Component
@Slf4j
public class JobLogTap extends UnsynchronizedAppenderBase<ILoggingEvent> {

    static final String APPENDER_NAME = "JOB_RUN_TAP";
    private static final int MAX_MESSAGE = 1000;

    @PostConstruct
    void install() {
        try {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
                log.warn("Job ledger: logging is not Logback - errors logged by jobs will not be counted");
                return;
            }
            Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
            if (root.getAppender(APPENDER_NAME) != null) return;
            setContext(ctx);
            setName(APPENDER_NAME);
            start();
            root.addAppender(this);
        } catch (Exception e) {
            log.warn("Job ledger: could not attach the log tap - jobs will read SUCCEEDED even when "
                    + "they logged errors: {}", e.getMessage());
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        JobRunContext.ActiveRun run = JobRunContext.current();
        if (run == null) return;
        Level level = event.getLevel();
        if (level.isGreaterOrEqual(Level.ERROR)) {
            run.errorsLogged++;
            if (run.firstError == null) run.firstError = describe(event);
        } else if (level.isGreaterOrEqual(Level.WARN)) {
            run.warningsLogged++;
        }
    }

    static String describe(ILoggingEvent event) {
        String logger = event.getLoggerName();
        int dot = logger == null ? -1 : logger.lastIndexOf('.');
        String source = logger == null ? "" : logger.substring(dot + 1);
        StringBuilder sb = new StringBuilder();
        if (!source.isEmpty()) sb.append('[').append(source).append("] ");
        sb.append(event.getFormattedMessage());
        IThrowableProxy t = event.getThrowableProxy();
        if (t != null) {
            String cls = t.getClassName();
            sb.append(" (").append(cls.substring(cls.lastIndexOf('.') + 1)).append(')');
        }
        return JobOutcome.truncate(sb.toString(), MAX_MESSAGE);
    }
}
