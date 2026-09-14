package com.example.trading.universe;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic universe endpoints (SPEC §30).
 *
 * <p>The two GETs are DB-only and fast — safe for a dashboard page load. The scan is a
 * multi-minute, ~1,900-symbol Kite sweep and must never be wired into one (SPEC §27.4).
 */
@Slf4j
@RestController
@RequestMapping("/api/universe")
@RequiredArgsConstructor
public class UniverseController {

    private final UniverseExpansionService expansionService;
    private final IpoWatchService ipoWatchService;
    private final DynamicUniverseRepository repository;
    private final UniverseConfig config;
    private final MarketHoursService marketHoursService;

    /**
     * Latest hour at which a long Kite sweep may be started by hand on a weekday.
     *
     * <p>Stricter than the review's suggested 14:00 on purpose: the daily multibagger
     * screening fires <i>at</i> 14:00 and is itself a long Kite-heavy scan, so a sweep
     * launched at 13:59 would run straight into it and both would queue behind the same
     * ~2.9 req/s pacing gate (B-027). 13:00 leaves the scan room to finish first.
     */
    private static final int WEEKDAY_LATEST_START_HOUR = 13;

    /**
     * After this, no manual Kite work may begin on a weekday.
     *
     * <p>14:00 is where the daily multibagger screening starts, so it is already the point
     * past which the broker is spoken for. IPO watch is measured at <b>6 minutes / ~1,300
     * paced calls</b> for 656 listings (SPEC §30.6) — not the "quick GET" its shape suggests
     * — so a 14:55 cutoff would have left it finishing inside the 15:05 report window.
     */
    private static final LocalTime CRUNCH_START = LocalTime.of(14, 0);

    /** Everything the funnel is tracking, grouped by status. DB-only. */
    @GetMapping("/dynamic")
    public Map<String, Object> dynamic() {
        List<DynamicUniverseEntity> all = repository.findAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", config.isEnabled());
        out.put("scanEnabled", config.isScanEnabled());
        out.put("maxActiveSymbols", config.getMaxActiveSymbols());
        out.put("promoted", all.stream().filter(d -> d.isActive() && "PROMOTED".equals(d.getStatus())).toList());
        out.put("queued", all.stream().filter(d -> d.isActive() && "QUEUED".equals(d.getStatus())).toList());
        // Retired rows are returned, not hidden: a funnel that only shows its winners
        // tells you nothing about whether it works (SPEC §25.1 survivorship).
        out.put("retired", all.stream().filter(d -> !d.isActive()).toList());
        out.put("promotedLast7Days", repository.findPromotedSince(LocalDate.now().minusDays(7)));
        if (!config.isEnabled()) {
            out.put("note", "Expansion is in observation mode: symbols are discovered, queued and "
                    + "scored, but do not yet enter the screening universe "
                    + "(trading.universe.dynamic-expansion.enabled=false).");
        }
        return out;
    }

    /**
     * Recent mainboard listings and whether the post-IPO base setup is present.
     *
     * <p>Hits Kite for candles per listing, so it is slower than a dashboard page-load
     * budget allows — the UI calls it behind an explicit action, not on load.
     */
    @GetMapping("/ipo-watch")
    public Map<String, Object> ipoWatch(@RequestParam(defaultValue = "false") boolean setupsOnly) {
        requireOutsideAfternoonCrunch("IPO watch");
        List<IpoWatchService.IpoCandidate> rows = ipoWatchService.recentListings(setupsOnly);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowMonths", config.getRecentIpoMonths());
        out.put("maturityMonths", config.getMinMonthsSinceListing());
        out.put("setupsOnly", setupsOnly);
        out.put("count", rows.size());
        out.put("listings", rows);
        return out;
    }

    /**
     * Run the coarse scan now. Sweeps ~1,900 symbols through the paced Kite gate — expect
     * several minutes. Never call from a page load.
     */
    @PostMapping("/scan")
    public UniverseExpansionService.ScanResult scan() {
        requireRunwayForLongSweep("coarse scan");
        log.info("API: manual universe coarse scan requested");
        return expansionService.runCoarseScan();
    }

    /*
     * Why these two guards exist, and why they differ (B-049).
     *
     * The 15:00-15:30 window is densely packed — 15:05 target-hit, 15:15 holdings, 15:22
     * recommendation outcomes, 15:25 accuracy email, 15:28 tax capture — and every one of
     * them aborts silently once isMarketOpen() goes false at 15:30 (B-014). Kite calls are
     * paced process-wide at ~2.9 req/s (B-027), so a manual sweep does not merely run
     * slowly: it holds that gate and starves those jobs, and the damage lands on a report
     * the user was expecting rather than on the request that caused it.
     *
     * The endpoints get different guards because they cost different amounts. The coarse
     * scan is ~1,600 symbols and a measured 22 minutes, so it needs runway before the 14:00
     * screening. IPO watch (measured 6 minutes) and Stage B queue processing are shorter but
     * still far from free, so they are refused from 14:00 onward rather than needing a
     * cutoff of their own.
     */

    /** Long sweep: Saturday window, or a weekday before {@link #WEEKDAY_LATEST_START_HOUR}. */
    private void requireRunwayForLongSweep(String what) {
        if (marketHoursService.isSaturdayScreeningWindow()) return;

        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        if (marketHoursService.isMarketOpen() && now.getHour() < WEEKDAY_LATEST_START_HOUR) return;

        throw new SweepBlockedException(String.format(
                "Universe %s is a ~20-minute Kite sweep and cannot start at %s IST. It would hold "
                        + "the shared broker rate limit through the 15:05-15:28 report jobs, which "
                        + "abort silently at 15:30. Run it before %02d:00 IST on a weekday, or in "
                        + "the Saturday 07:45-10:30 window.",
                what, now.toLocalTime().withNano(0), WEEKDAY_LATEST_START_HOUR));
    }

    /** Shorter sweep: anything except the 14:00-close stretch the broker is already spoken for. */
    private void requireOutsideAfternoonCrunch(String what) {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        LocalTime t = now.toLocalTime();
        if (t.isBefore(CRUNCH_START) || !marketHoursService.isMarketOpen()) return;

        throw new SweepBlockedException(String.format(
                "Universe %s makes live Kite calls and is blocked between %s and market close: the "
                        + "14:00 screening and the 15:05-15:28 report jobs need the shared broker rate "
                        + "limit, and those jobs abort silently at 15:30. Try again after the close, or "
                        + "before %s IST.",
                what, CRUNCH_START, CRUNCH_START));
    }

    /**
     * Refusal carrying its own reason.
     *
     * <p>Not {@code ResponseStatusException}: {@code server.error.include-message} defaults to
     * {@code never}, so Spring's default error body drops the reason and the caller receives a
     * bare 409. A guard whose explanation never reaches the person it stops is the silent
     * failure this whole entry is about.
     */
    static class SweepBlockedException extends RuntimeException {
        SweepBlockedException(String message) { super(message); }
    }

    @ExceptionHandler(SweepBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleBlocked(SweepBlockedException e) {
        log.info("Universe sweep refused: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 409);
        body.put("error", "Blocked");
        body.put("reason", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Deep-score the next batch of queued symbols now. */
    @PostMapping("/process-queue")
    public UniverseExpansionService.PromotionResult processQueue() {
        requireOutsideAfternoonCrunch("queue processing");
        log.info("API: manual universe queue processing requested");
        return expansionService.processQueue();
    }
}
