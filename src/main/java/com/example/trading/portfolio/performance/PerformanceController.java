package com.example.trading.portfolio.performance;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
 * Portfolio truth (SPEC §46). The two GETs are DB-only and safe on a page load; the backfill
 * is a manual Kite call and lives under {@code /api/portfolio}, not {@code /api/dashboard},
 * because nothing under the dashboard prefix may touch the broker (SPEC §20 rule 7).
 */
@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PerformanceController {

    /** Bounded at both ends (Gotcha 97): the guard covers the contention, not the moment of asking. */
    private static final LocalTime KITE_CRUNCH_START = LocalTime.of(14, 0);
    private static final LocalTime KITE_CRUNCH_END = LocalTime.of(15, 30);

    private final PortfolioPerformanceService performanceService;
    private final PortfolioSnapshotService snapshotService;
    private final MarketHoursService marketHoursService;

    /** Time-weighted return vs Nifty 50 and Nifty Midcap 150, total return, drawdown, cash, lot coverage. */
    @GetMapping("/performance")
    public PerformanceDto.PerformanceResponse performance(@RequestParam(defaultValue = "365") int days) {
        return performanceService.performance(Math.max(30, Math.min(days, 1095)));
    }

    /** Portfolio-weighted P/E, ROCE, ROE and profit growth, each with its coverage. */
    @GetMapping("/quality")
    public PerformanceDto.PortfolioQualityResponse quality() {
        return performanceService.quality();
    }

    /**
     * Fill benchmark history from Kite daily candles - two paced calls. Refused 14:00-15:30 on a
     * trading day with a 409 carrying its reason (B-049 pattern).
     */
    @PostMapping("/benchmark/backfill")
    public Map<String, Object> backfill(@RequestParam(defaultValue = "400") int days) {
        requireOutsideKiteCrunch();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("written", snapshotService.backfillBenchmarks(days));
        out.put("days", days);
        return out;
    }

    private void requireOutsideKiteCrunch() {
        LocalTime now = ZonedDateTime.now(marketHoursService.getMarketZone()).toLocalTime();
        boolean inside = !now.isBefore(KITE_CRUNCH_START) && now.isBefore(KITE_CRUNCH_END);
        if (inside && marketHoursService.isMarketOpen()) {
            throw new BlockedException(String.format("The benchmark backfill makes live broker calls and is blocked "
                    + "between %s and %s IST: the 14:00 screening and the 15:05-15:28 report jobs share the Kite "
                    + "rate limit. Run it before %s or after the close.", KITE_CRUNCH_START, KITE_CRUNCH_END, KITE_CRUNCH_START));
        }
    }

    static class BlockedException extends RuntimeException {
        BlockedException(String m) { super(m); }
    }

    @ExceptionHandler(BlockedException.class)
    public ResponseEntity<Map<String, Object>> blocked(BlockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "BLOCKED", "reason", e.getMessage()));
    }
}
