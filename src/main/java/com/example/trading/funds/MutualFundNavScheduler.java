package com.example.trading.funds;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls AMFI's daily NAV file at 12:30 on weekdays (SPEC §52.6, §15).
 *
 * <p><b>Why 12:30.</b> It is the only genuine weekday gap left — between the 12:15 IPO capture and
 * the 13:00 watchlist run — and this job makes <b>no Kite call and no NSE call</b>, so it competes
 * for neither the ~2.9 req/s broker gate whose exhaustion has twice starved the afternoon jobs
 * (B-014, B-049) nor the NSE session the 09:45 and 10:00 jobs share. One HTTP GET to a third host.
 * That is the same argument §15 records for the 11:30 fundamentals backfill.
 *
 * <p><b>Why a mid-day pull is correct rather than stale.</b> A mutual fund has exactly one price a
 * day, struck after the cut-off and published around 23:00, so this necessarily reads the
 * <em>previous</em> business day. There is no intraday NAV to miss, and no slot inside the §3.4
 * window could ever see today's. The series is complete with a one-day lag, which a multi-year
 * measurement does not notice.
 *
 * <p>Guarded with {@code isMarketOpen()} as its first statement like every other scheduled method
 * (§3.4) — defence in depth against a cron edited wrongly later, and against the 15:30–15:45
 * shutdown ramp.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MutualFundNavScheduler {

    private final MarketHoursService marketHoursService;
    private final MutualFundNavSyncService syncService;
    private final MutualFundConfig config;

    @Scheduled(cron = "0 30 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void pullDailyNav() {
        if (!marketHoursService.isMarketOpen()) {
            return; // Outside 09:15-15:30 IST - SPEC §3.4.
        }
        if (!config.isNavPullEnabled()) {
            log.info("AMFI NAV pull is disabled by configuration - skipping");
            return;
        }

        MutualFundNavSyncService.SyncResult result = syncService.sync();
        if (!result.succeeded()) {
            // Recorded on mf_sync_run as well, so the data-health screen can name the job rather
            // than leaving a freshness stamp that has simply stopped moving (B-116).
            log.error("AMFI NAV pull failed: {}", result.failureReason());
        }
    }
}
