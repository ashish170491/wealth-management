package com.example.trading.valuation;

import com.example.trading.jobs.JobRerun;
import com.example.trading.jobs.JobRunContext;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Writes the method-validation snapshot at 12:35 on weekdays (SPEC §57.6, §15).
 *
 * <p><b>Why its own job and not a hook in the Saturday screening.</b> That run already ends as
 * completed-with-errors routinely, so an error here would not be heard; its hook would sit behind
 * the Kite-token guard though this work is database-only; and a third caller of the Saturday window
 * turns the exception into a convention (Gotcha 28). This job is a weekday job inside 09:15-15:30,
 * has its own {@code job_run} row and outcome on the jobs page, needs no broker session, and heals
 * itself: a missed day is simply captured the next weekday.
 *
 * <p><b>Why 12:35.</b> It sits between the 12:30 AMFI pull and the 12:40 macro read. It makes no
 * Kite call and no NSE call and takes seconds (SPEC §15 to be re-checked at implementation).
 *
 * <p>The scheduled method is guards and then a call to the {@link JobRerun} method, so the schedule
 * and the jobs-page button run the same code (Gotcha 156).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValuationGapSnapshotScheduler {

    private final MarketHoursService marketHoursService;
    private final ValuationGapSnapshotService snapshotService;
    private final ValuationGapConfig config;

    @Scheduled(cron = "0 35 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureWeekdays() {
        if (!marketHoursService.isMarketOpen()) {
            return; // Outside 09:15-15:30 IST - SPEC §3.4.
        }
        if (!config.isSnapshotEnabled()) {
            log.info("Valuation-gap snapshot is disabled by configuration - skipping");
            JobRunContext.skip("Switched off in config (valuation-gap.snapshot-enabled is false)");
            return;
        }
        runNow();
    }

    /** The work of {@link #captureWeekdays()} without its guards (SPEC 56.9). */
    @JobRerun("captureWeekdays")
    public void runNow() {
        ValuationGapSnapshotService.CaptureResult result = snapshotService.captureNow(
                LocalDate.now(marketHoursService.getMarketZone()), ValuationGapSnapshotService.SCHEDULED);
        if (result.skipped()) {
            JobRunContext.skip(result.skipReason());
        }
    }
}
