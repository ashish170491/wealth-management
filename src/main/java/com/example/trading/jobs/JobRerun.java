package com.example.trading.jobs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method the jobs page calls to re-run a scheduled job (SPEC §56.9).
 *
 * <p>Put it on a public, no-argument method <b>in the same class</b> as the {@code @Scheduled}
 * method it re-runs, and name that method in {@link #value()}. The scheduled method must then be
 * nothing but its guards followed by a call to this one, so the button and the schedule run the
 * same code and cannot drift apart. The method must not call {@code isMarketOpen()} as a guard:
 * a re-run usually happens after the close, and a guard would make it do nothing while reporting
 * success.
 *
 * <p>{@code JobRerunCoverageTest} fails if a scheduled method has neither one of these nor a
 * stated reason in {@link JobRerunPolicy#NOT_RERUNNABLE}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface JobRerun {

    /** The name of the {@code @Scheduled} method this re-runs, e.g. {@code "analyzeHoldings"}. */
    String value();
}
