package com.example.trading.fundamentals;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the universe-wide annual-history backfill (SPEC §32.6).
 *
 * <p><b>Why this defaults to enabled</b>, unlike every recent addition. Gotcha 30's shadow-mode
 * rule governs new <em>scoring</em> signals: a bonus re-ranks picks the day it ships, so it must
 * be measured before it steers anything. This changes no score and produces no verdict — it
 * fetches filings a company already published and stores them. Withholding data the analysis
 * already knows how to read is not caution, it is just a gap that reads as "nothing was checked"
 * (Gotcha 44).
 *
 * <p>It does have a real second-order effect, and §9 of the plan states it plainly: as depth
 * arrives, forensic checks that need 3-4 years start reporting, in both directions — new true
 * flags appear, and false {@code DILUTION:HIGH} flags disappear as the B-066 discriminator
 * finally has the four years it needs.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "trading.fundamentals.backfill")
public class FundamentalsBackfillConfig {

    /** Master switch for the scheduled batch. The manual endpoints are unaffected. */
    private boolean enabled = true;

    /**
     * Symbols per weekday batch. Thirty at ~11 requests each is ~330 requests, about 6.5 minutes
     * at the default pacing — short enough to finish well inside its slot, large enough that the
     * ~370-symbol universe converges in roughly thirteen weekdays.
     */
    private int batchSize = 30;

    /**
     * Milliseconds between bulk NSE requests, applied process-wide (see {@code NseDataService.pace}).
     *
     * <p>1200 ms is a deliberately cautious starting point, not a measured optimum. The one
     * measured comparable in this repo is 3,200 <em>paced Kite</em> calls in 22 minutes
     * (~0.41 s each), and NSE archive XBRL documents are far heavier while NSE is much the more
     * bot-sensitive host — it has already walled {@code /api/quote-equity} permanently (B-018).
     * Lower this only against measurement, and remember the cost of being wrong is asymmetric:
     * a slow backfill finishes a week later, a walled endpoint never finishes at all.
     */
    private long paceMs = 1200L;

    /** Newest N financial years to fetch per symbol. */
    private int maxYears = 10;

    /** Give up on a symbol after this many failed attempts, so one broken name cannot eat the budget. */
    private int maxAttempts = 3;

    /** Days before a FAILED symbol becomes eligible again. */
    private int retryAfterDays = 7;

    /**
     * Latest clock time the batch may still be issuing requests.
     *
     * <p>The batch stops here rather than only checking its start time. The 14:00 screening and
     * the 14:45 insider capture are both NSE-heavy, and the 15:05-15:28 block aborts silently at
     * 15:30 (B-014) if something upstream starves it. A guard that only checks whether it is
     * allowed to *begin* does not prevent the interference it exists to prevent — which is
     * exactly what defect 7.2 found in the existing endpoints.
     */
    private String stopAfter = "13:30";

    @PostConstruct
    void applyPacing() {
        com.example.trading.ai.NseDataService.setArchivePaceMs(paceMs);
        log.info("Fundamentals backfill: enabled={} batchSize={} paceMs={} maxYears={} stopAfter={}",
                enabled, batchSize, com.example.trading.ai.NseDataService.getArchivePaceMs(),
                maxYears, stopAfter);
    }
}
