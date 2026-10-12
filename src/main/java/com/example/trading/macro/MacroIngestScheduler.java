package com.example.trading.macro;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads the news once a weekday (SPEC 48.9, revised 2026-10-12).
 *
 * <p>Before this nothing scheduled the macro reader: the feed scan stored headlines every 15
 * minutes and nobody read them, so from 2026-09-12 the event window was empty and every holding
 * drew "checked, nothing applies" (B-154). B-154 made an unread window say "not measured"; this
 * makes the window read.
 *
 * <p>12:40 is a gap in the SPEC 15 schedule (after the 12:30 AMFI pull, before the 13:00 watchlist
 * run) and outside both of the ingest's own refusal windows (09:40-10:15 and from 14:00). It uses
 * RSS hosts, not NSE, plus one Kite quote when readings are filed - far inside the broker budget.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacroIngestScheduler {

    private final MacroIngestService ingestService;
    private final MacroConfig config;
    private final MarketHoursService marketHoursService;

    @Scheduled(cron = "0 40 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyIngest() {
        if (!marketHoursService.isMarketOpen()) {
            return; // Outside 09:15-15:30 IST - SPEC 3.4.
        }
        if (!config.getIngest().isScheduled()) {
            log.debug("Macro ingest: scheduled read is off in config");
            return;
        }
        try {
            MacroIngestService.IngestResult r = ingestService.ingest();
            log.info("Macro ingest (scheduled): {}", r.note());
        } catch (Exception e) {
            log.error("Macro ingest (scheduled) failed - the news stays unread today and every "
                    + "holding will read 'not measured' once the window passes: {}", e.getMessage(), e);
        }
    }
}
