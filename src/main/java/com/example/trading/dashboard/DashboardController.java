package com.example.trading.dashboard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * JSON feed for the dashboard UI (SPEC section 27).
 *
 * <p><b>Read-only, enforced by SPEC section 20 rule 7.</b> No endpoint here may write to the
 * database, call the broker, call an external API, or send email. Everything below is a
 * Postgres read, so it stays under the 2-second page-load budget in SPEC section 18.
 *
 * <p><b>Never call these from a page load</b> - they are why the read-only rule exists
 * (full list in SPEC section 27.4):
 * <ul>
 *   <li>{@code GET /api/multibagger/screen/{symbol}} - 5-20s and writes a score row</li>
 *   <li>{@code GET /api/multibagger/screen/tier/{tier}} - 369 symbols, 30+ MINUTES</li>
 *   <li>{@code GET /api/research/*} - sends an email, except the six safe ones
 *       (/levels, /analyst, /valuation, /earnings, /capital-efficiency, /shareholding)</li>
 *   <li>{@code GET /api/accuracy/dimension-ic} - hits Kite despite being a GET</li>
 *   <li>{@code GET /api/fiidii/report} (live NSE fetch) and {@code /debug-raw} (clears caches)
 *       - use {@code /api/fiidii/summary} instead</li>
 *   <li>{@code POST /api/trading/breakout/scan} (3-6 min + email),
 *       {@code POST /api/trading/holdings/train-models} (spawns Python),
 *       {@code DELETE /api/portfolio/tax-lots/all} (destructive)</li>
 * </ul>
 *
 * <p><b>Why {@code symbol} is a query parameter, not a path variable:</b> symbols look like
 * {@code NSE:RELIANCE}. A colon is legal in a path segment per RFC 3986 but is an encoding
 * landmine across Tomcat, Spring and {@code fetch()}. Keep it in the query string.
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    /** Guards against a caller asking for more history than the retention window holds. */
    private static final int MAX_DAYS = 1095;

    private final DashboardService dashboardService;

    /**
     * Server reachability plus per-table data freshness. Loaded first by every screen.
     *
     * <p>These are two independent things: a live server can serve Friday's prices on a
     * Monday morning, so the UI stamps freshness separately from connection state
     * (SPEC section 27.7).
     */
    @GetMapping("/health")
    public DashboardDto.HealthResponse health() {
        return dashboardService.health();
    }

    /**
     * Every automated data check the app can run on itself (SPEC 44): whether each table is as
     * new as its own cron says it should be, whether each scoring signal was actually measured
     * and whether its answer varies, and how deep the annual-accounts history is.
     *
     * <p>DB-only and read-only like everything else here, but heavier than its neighbours - it
     * resolves the whole screening universe for the depth histogram. It backs one screen the
     * reader opens deliberately, not a strip on every page.
     */
    @GetMapping("/data-health")
    public DashboardDto.DataHealthResponse dataHealth() {
        return dashboardService.dataHealth();
    }

    /**
     * Landing-page aggregate: portfolio KPIs, risk headline, accuracy headline, and the
     * merged attention list. One request instead of six, so the landing page has no N+1
     * fan-out. Sanctioned by SPEC section 25.6.
     */
    @GetMapping("/summary")
    public DashboardDto.SummaryResponse summary() {
        return dashboardService.summary();
    }

    /**
     * Latest multibagger screening that has rows, with the date it came from.
     *
     * <p>Use this rather than {@code /api/multibagger/scores} (in-memory cache, empty after
     * the daily restart) or {@code /api/multibagger/history} (defaults to today, empty
     * until the 14:00 run).
     */
    @GetMapping("/screener")
    public DashboardDto.ScreenerResponse screener() {
        return dashboardService.screener();
    }

    /**
     * Per-stock history for the drill-down charts, from {@code holdings_history}.
     *
     * <p>Gaps are returned as genuinely missing dates, never interpolated - the snapshot job
     * only writes on days the app ran, and there are known holes (the Aug 19/21 2026 outage).
     * The client must render a gap as a gap rather than inventing a price.
     */
    /**
     * "Can this business compound?" for one stock (SPEC 41). DB-only, page-load safe.
     * {@code symbol} is a query parameter throughout this API - symbols contain a colon.
     */
    @GetMapping("/compounding")
    public ResponseEntity<Map<String, Object>> compounding(@RequestParam String symbol) {
        Map<String, Object> out = dashboardService.compounding(symbol);
        return out == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(out);
    }

    @GetMapping("/series/holding")
    public List<DashboardDto.HoldingSeriesPoint> holdingSeries(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "180") int days) {
        return dashboardService.holdingSeries(symbol, clampDays(days));
    }

    /** Portfolio equity curve: invested cost, market value and P&amp;L per date. */
    @GetMapping("/series/portfolio")
    public List<DashboardDto.PortfolioSeriesPoint> portfolioSeries(
            @RequestParam(defaultValue = "180") int days) {
        return dashboardService.portfolioSeries(clampDays(days));
    }

    /**
     * Every symbol at once, for the holdings-table sparklines. Deliberately one request for
     * the whole table rather than one per row.
     */
    @GetMapping("/series/matrix")
    public Map<String, List<DashboardDto.HoldingSeriesPoint>> holdingSeriesMatrix(
            @RequestParam(defaultValue = "90") int days) {
        return dashboardService.holdingSeriesMatrix(clampDays(days));
    }

    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, MAX_DAYS));
    }
}
