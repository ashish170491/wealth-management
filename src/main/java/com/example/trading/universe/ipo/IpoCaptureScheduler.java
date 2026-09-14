package com.example.trading.universe.ipo;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Daily IPO capture (SPEC §45.6) at 12:15 IST — inside the §3.4 window and in a genuine gap:
 * clear of the NSE-heavy 09:45 / 10:00 / 11:30 jobs, of the 14:00 screening and the 14:45
 * insider capture, and of the packed 15:00–15:30 close ramp. The only other job near it is the
 * 12:00 exit-timing check, which is Kite-light and finishes in seconds.
 *
 * <p>Cost: three NSE list calls, one paced detail call per issue in the pipeline plus a bounded
 * backfill, and one paced Kite quote per listing under a year old (~150 at most, under a minute).
 * A wall-clock stop at {@link #IN_MARKET_STOP_AFTER} keeps a slow day from reaching 14:00
 * (Gotcha 97: the guard covers the contention, not the moment of asking).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpoCaptureScheduler {

    private final IpoTrackingService trackingService;
    private final MarketHoursService marketHoursService;

    /** Latest a scheduled run may still be making calls; the 14:00 screening owns the broker after this. */
    static final LocalTime IN_MARKET_STOP_AFTER = LocalTime.of(13, 45);

    @Scheduled(cron = "0 15 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureDaily() {
        if (!marketHoursService.isMarketOpen()) {
            return; // Outside 09:15-15:30 IST — SPEC §3.4.
        }
        try {
            IpoTrackingService.CaptureResult r = trackingService.capture(this::shouldStop);
            log.info("Scheduled IPO capture done: {}", r.note());
        } catch (Exception e) {
            log.error("Scheduled IPO capture failed: {}", e.toString(), e);
        }
    }

    /** True once the run has strayed into the window the 14:00 screening needs. */
    boolean shouldStop() {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        return marketHoursService.isMarketOpen() && !now.toLocalTime().isBefore(IN_MARKET_STOP_AFTER);
    }
}
