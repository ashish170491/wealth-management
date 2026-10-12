package com.example.trading.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * How long a job usually takes, and how late it usually starts (SPEC §56.6). Pure.
 *
 * <p>Only runs that did their work count toward duration. A SKIPPED run takes ~2 ms by
 * construction, and the 15-minute headline capture refuses two of its 28 daily fires by design,
 * so averaging skips in would make every frequent job look several times faster than it is.
 * RUNNING and INTERRUPTED runs have no honest duration and are left out too.
 *
 * <p>The middle value (median) is the headline, not the mean: one 40-minute screening on a day the
 * broker was throttling should not move "typical" for a fortnight. The 90th percentile is withheld
 * below {@link #MIN_FOR_P90} runs, where it is simply the slowest run wearing a statistician's name.
 */
public record JobStats(int runsMeasured, Long medianMs, Long p90Ms, Long maxMs, Long medianLagMs, Long maxLagMs) {

    public static final int MIN_FOR_P90 = 5;

    public record Sample(JobOutcome outcome, Long durationMs, Long startLagMs) {
    }

    public static JobStats of(List<Sample> samples) {
        List<Long> durations = new ArrayList<>();
        List<Long> lags = new ArrayList<>();
        for (Sample s : samples) {
            if (s == null || s.outcome() == null) continue;
            boolean worked = s.outcome() == JobOutcome.SUCCEEDED
                    || s.outcome() == JobOutcome.COMPLETED_WITH_ERRORS
                    || s.outcome() == JobOutcome.FAILED;
            if (worked && s.durationMs() != null) durations.add(s.durationMs());
            // Lag is about the scheduler, not the job, so a skipped run's lag is just as real.
            if (s.startLagMs() != null && s.outcome() != JobOutcome.RUNNING) lags.add(s.startLagMs());
        }
        Collections.sort(durations);
        Collections.sort(lags);
        return new JobStats(durations.size(),
                percentile(durations, 50),
                durations.size() >= MIN_FOR_P90 ? percentile(durations, 90) : null,
                durations.isEmpty() ? null : durations.get(durations.size() - 1),
                percentile(lags, 50),
                lags.isEmpty() ? null : lags.get(lags.size() - 1));
    }

    /** Nearest-rank percentile of an already-sorted list; null when empty. */
    static Long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return null;
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return Objects.requireNonNull(sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1))));
    }
}
