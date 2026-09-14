package com.example.trading.analyst;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Daily analyst-target capture and measurement (SPEC §49.6) at 13:20 IST.
 *
 * <p><b>Why 13:20.</b> Inside the §3.4 window and in a genuine gap. The 13:00 trio (holdings
 * report, watchlist analysis, holdings prices) has finished by then, the 12:15 IPO capture stops
 * itself at 13:45, and the 14:00 screening — which owns the broker budget for the rest of the
 * afternoon — has not begun. It is also deliberately clear of the 09:45/10:00 NSE jobs, though
 * this job touches NSE not at all.
 *
 * <p><b>Cost.</b> The capture half is database-only: it mines headlines another job already
 * fetched. The measurement half is one paced broker call per stock with an open target on a cold
 * cache, bounded by {@code max-measurements-per-run} and rotating least-recently-measured first,
 * so the whole open book is covered over a few days rather than its head being re-read daily
 * (B-074's lesson about rotation).
 *
 * <p>A wall-clock stop at {@link #STOP_AFTER} keeps a slow day out of the 14:00 screening's way:
 * the reason for the guard is contention, so the guard covers the contention rather than the
 * moment of asking (Gotcha 97, 101).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalystTargetScheduler {

    /** Latest a scheduled run may still be making broker calls. */
    static final LocalTime STOP_AFTER = LocalTime.of(13, 50);

    private final AnalystTargetCaptureService captureService;
    private final BrokerResearchCaptureService feedCaptureService;
    private final AnalystTargetOutcomeService outcomeService;
    private final AnalystTargetConfig config;
    private final MarketHoursService marketHoursService;

    @Scheduled(cron = "0 20 13 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureAndMeasure() {
        if (!marketHoursService.isMarketOpen()) {
            return; // Outside 09:15-15:30 IST — SPEC §3.4.
        }
        if (!config.isEnabled()) return;

        // The structured feed first: it publishes what the headline parser can only infer, so a
        // call captured here is already on file (same dedup key) when the headline for the same
        // call is mined below - which is what stops one note being counted as two (SPEC 49.11).
        if (config.isFeedEnabled()) {
            try {
                BrokerResearchCaptureService.FeedResult feed = feedCaptureService.captureLatest();
                log.info("Analyst research feed: {} rows read, {} written, {} already on file",
                        feed.rowsRead(), feed.written(), feed.duplicates());
            } catch (Exception e) {
                log.error("Analyst research feed capture failed: {}", e.toString(), e);
            }
        }

        try {
            AnalystTargetCaptureService.CaptureResult capture = captureService.mineStoredHeadlines();
            log.info("Analyst target capture: {}", capture.note());
        } catch (Exception e) {
            log.error("Analyst target capture failed: {}", e.toString(), e);
        }

        try {
            AnalystTargetOutcomeService.MeasurementResult measured =
                    outcomeService.measureOpenTargets(this::shouldStop);
            log.info("Analyst target measurement: {}", measured.note());
        } catch (Exception e) {
            log.error("Analyst target measurement failed: {}", e.toString(), e);
        }
    }

    /** True once the run has strayed into the window the 14:00 screening needs. */
    boolean shouldStop() {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        LocalTime limit = config.stopAfterTime() != null ? config.stopAfterTime() : STOP_AFTER;
        return marketHoursService.isMarketOpen() && !now.toLocalTime().isBefore(limit);
    }
}
