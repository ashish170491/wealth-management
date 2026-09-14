package com.example.trading.analyst;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The analyst target ledger (SPEC §49.8).
 *
 * <p>The three GETs are database-only and safe on page load. The two POSTs write, and
 * {@code /measure} makes paced broker calls, so it is refused inside the window the screening
 * and close-ramp jobs need — with the reason in the body, because
 * {@code server.error.include-message} is {@code never} and a bare 409 is a guard whose
 * explanation never reaches the person it is guarding against (B-049).
 *
 * <p>{@code symbol} is a query parameter throughout — symbols carry a colon.
 */
@Slf4j
@RestController
@RequestMapping("/api/analyst")
@RequiredArgsConstructor
public class AnalystTargetController {

    /** From here the 14:00 screening and the 15:00-15:30 ramp own the broker budget. */
    private static final LocalTime BROKER_CRUNCH_START = LocalTime.of(14, 0);

    private final AnalystTargetViewService viewService;
    private final AnalystTargetCaptureService captureService;
    private final AnalystTargetOutcomeService outcomeService;
    private final BrokerResearchCaptureService feedCaptureService;
    private final AnalystTargetConfig config;

    private final MarketHoursService marketHoursService;

    // ------------------------------------------------------------------ reads (page-load safe)

    /** Every recorded target for one stock, plus what the open ones say. DB-only. */
    @GetMapping("/targets")
    public Map<String, Object> forStock(@RequestParam String symbol) {
        return viewService.forStock(symbol);
    }

    /**
     * The per-house scoreboard: hit rate, median excess return over the Nifty, revision rate.
     *
     * <p>DB-only. A house below the resolved-call floor reports {@code TOO_EARLY} and no hit rate
     * — an absent record rather than a bad one.
     */
    @GetMapping("/track-record")
    public Map<String, Object> trackRecord() {
        return viewService.trackRecord();
    }

    /**
     * Where this app's screening and the brokerages' open targets agree and disagree
     * (SPEC §49.12). DB-only and page-load safe.
     *
     * <p>Reports no view on whether any target will be reached. It reports where two independent
     * measurements of the same stocks point different ways — and says why they mostly will.
     */
    @GetMapping("/overlap")
    public Map<String, Object> overlap() {
        return viewService.overlap();
    }

    /** Targets recorded recently across every stock. DB-only. */
    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(defaultValue = "90") int days,
                                      @RequestParam(defaultValue = "0") int limit) {
        return viewService.recent(days, limit);
    }

    // ------------------------------------------------------------------ writes (button only)

    /**
     * Mine the stored headline feed for targets now.
     *
     * <p>Database-only despite being a POST: the headlines were fetched by another job. It is a
     * POST because it writes, so no page load can reach it.
     */
    @PostMapping("/capture")
    public AnalystTargetCaptureService.CaptureResult capture() {
        return captureService.mineStoredHeadlines();
    }

    /**
     * Pull the latest notes from the structured broker-research feed now (SPEC §49.11).
     *
     * <p>A handful of external calls: it reads from the top and stops as soon as a page adds
     * nothing, which on an ordinary day is one page. Not refused during the broker crunch because
     * it touches neither the broker nor NSE - it is a different host with its own budget.
     */
    @PostMapping("/feed/capture")
    public ResponseEntity<?> captureFromFeed() {
        if (!config.isFeedEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(reason(
                    "The structured research feed is switched off "
                            + "(trading.analyst-targets.feed-enabled). The headline parser still runs."));
        }
        return ResponseEntity.ok(feedCaptureService.captureLatest());
    }

    /**
     * Walk the research archive and record everything in it (SPEC §49.11).
     *
     * <p>The one-off pass. Measured at roughly eighty-five pages back to January 2024, a few
     * thousand paced requests, several minutes. It writes only what is not already on file, so
     * re-running it is safe and cheap.
     *
     * <p>Refused during the broker crunch - not because it competes for the broker, but because
     * the rows it writes are measured by a pass that does, and a backfill finishing at 14:30
     * hands several thousand unpriced rows to a job with no budget left to price them.
     */
    @PostMapping("/feed/backfill")
    public ResponseEntity<?> backfillFromFeed(@RequestParam(defaultValue = "90") int maxPages) {
        if (!config.isFeedEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(reason(
                    "The structured research feed is switched off "
                            + "(trading.analyst-targets.feed-enabled)."));
        }
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        if (marketHoursService.isMarketOpen() && !now.toLocalTime().isBefore(BROKER_CRUNCH_START)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(reason(
                    "A backfill writes thousands of unpriced rows, and the pass that prices them "
                            + "shares the one broker rate limit with the 14:00 screening and the "
                            + "close-ramp jobs. Run it before 14:00, or outside a trading day."));
        }
        return ResponseEntity.ok(feedCaptureService.backfill(maxPages,
                () -> marketHoursService.isMarketOpen()
                        && !ZonedDateTime.now(marketHoursService.getMarketZone())
                        .toLocalTime().isBefore(BROKER_CRUNCH_START)));
    }

    /**
     * Re-derive which calls were revised, across the whole ledger (SPEC §49.11).
     *
     * <p>Database-only and idempotent. Needed after any out-of-order load, because the on-write
     * pass looks backwards and a newest-first archive walk gives it nothing to look at. Never
     * touches a call that already reached or missed its target.
     */
    @PostMapping("/reconcile-revisions")
    public Map<String, Object> reconcileRevisions() {
        int changed = captureService.reconcileSupersessions();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supersededNow", changed);
        m.put("note", changed == 0
                ? "Nothing to change - every earlier call by a house on a stock was already "
                        + "resolved or already marked revised."
                : changed + " earlier open calls are now marked as revised by a later call from "
                        + "the same house on the same stock. A revision is not a miss.");
        return m;
    }

    /**
     * Measure the open targets now.
     *
     * <p>One paced broker call per stock on a cold cache. Refused from 14:00 on a trading day.
     */
    @PostMapping("/measure")
    public ResponseEntity<?> measure(@RequestParam(defaultValue = "0") int limit) {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        if (marketHoursService.isMarketOpen() && !now.toLocalTime().isBefore(BROKER_CRUNCH_START)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(reason(
                    "The daily screening starts at 14:00 and the close-ramp jobs follow it, and they "
                            + "share the one broker rate limit this app has. Measuring targets now "
                            + "would slow them past 15:30, where they abort without saying so. "
                            + "Try again before 14:00, or tomorrow."));
        }
        return ResponseEntity.ok(outcomeService.measureOpenTargets(
                () -> marketHoursService.isMarketOpen()
                        && !ZonedDateTime.now(marketHoursService.getMarketZone())
                        .toLocalTime().isBefore(BROKER_CRUNCH_START),
                limit));
    }

    private static Map<String, Object> reason(String why) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", 409);
        m.put("error", "Not now");
        m.put("reason", why);
        return m;
    }
}
