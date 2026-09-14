package com.example.trading.fundamentals;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the annual-history backfill toward convergence, one weekday batch at a time
 * (SPEC §32.6, §15).
 *
 * <h2>Why 11:30 on a weekday, and not inside the Saturday screening</h2>
 * SPEC §40.3 originally proposed invoking this from inside {@code weeklyFullScreening()} to avoid
 * a new Saturday scheduler (Gotcha 28/35). That instinct is right about Gotcha 28 and wrong about
 * capacity. The Saturday app window is 07:50-10:35 and that job already carries a full screening
 * run, the coverage vector, shadow composites, the retirement pass and the Stage A coarse scan —
 * Stage A alone is a measured 11-22 minutes — with the weekly report following at 09:00. Roughly
 * 4,000 paced NSE requests do not fit, and crowding that window pushes the report past the point
 * where its guard aborts it in silence (B-014).
 *
 * <p><b>A weekday job is not the Saturday exception.</b> Gotcha 28 protects
 * {@code isSaturdayScreeningWindow()} from acquiring a third caller. This uses the ordinary
 * {@code isMarketOpen()} guard inside the §3.4 window and never touches the carve-out. It also
 * runs five times a week rather than once, which is the difference between converging in three
 * weeks and converging in four months.
 *
 * <p>11:30 is a genuine gap in the §15 schedule, between the 11:00 pair and the 12:00 exit
 * alerts, and clear of every NSE-heavy job: the 09:45 FII/DII fetch, its 10:00 report, the 10:00
 * quantitative discovery scan, the 14:00 screening and the 14:45 insider capture. It uses
 * <b>no Kite calls at all</b>, so it does not compete for the ~2.9 req/s process-wide broker
 * budget (Gotcha 23) that has twice starved the afternoon jobs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundamentalsBackfillScheduler {

    private final FundamentalsBackfillService backfillService;
    private final FundamentalsBackfillConfig config;
    private final MarketHoursService marketHoursService;

    @Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Kolkata")
    public void backfillBatch() {
        if (!marketHoursService.isMarketOpen()) {
            return; // Outside 09:15-15:30 IST — SPEC §3.4.
        }
        if (!config.isEnabled()) {
            log.debug("Fundamentals backfill: disabled in config");
            return;
        }
        try {
            FundamentalsBackfillService.BatchResult r = backfillService.runBatch(config.getBatchSize());
            if (r.symbolsAttempted() == 0 && r.remainingPending() == 0) {
                log.info("Fundamentals backfill: universe converged — nothing left to fetch. "
                        + "The table now maintains itself from the annual filing on each "
                        + "screening run (Gotcha 49).");
            }
        } catch (Exception e) {
            log.error("Fundamentals backfill batch failed: {}", e.getMessage(), e);
        }
    }
}
