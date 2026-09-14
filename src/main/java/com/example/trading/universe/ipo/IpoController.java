package com.example.trading.universe.ipo;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * IPO endpoints (SPEC §45.7). The three GETs are DB-only and page-load safe; the two POSTs make
 * live NSE and Kite calls and are refused, with the reason in the body, inside the windows other
 * jobs need (B-049 pattern).
 *
 * <p>{@code symbol} is a query parameter throughout — symbols carry a colon.
 */
@Slf4j
@RestController
@RequestMapping("/api/ipo")
@RequiredArgsConstructor
public class IpoController {

    private final IpoTrackingService trackingService;
    private final MarketHoursService marketHoursService;

    /** The FII/DII fetch at 09:45 and its report at 10:00 share the single NSE session. */
    private static final LocalTime NSE_CRUNCH_START = LocalTime.of(9, 40);
    private static final LocalTime NSE_CRUNCH_END = LocalTime.of(10, 15);
    /** From the 14:00 screening to the close, the broker and NSE are spoken for. */
    private static final LocalTime AFTERNOON_CRUNCH_START = LocalTime.of(14, 0);

    /** Open, forthcoming and just-closed issues with structure read and application sizing. DB-only. */
    @GetMapping("/pipeline")
    public Map<String, Object> pipeline() {
        return trackingService.pipelineView();
    }

    /** Listings inside the window with their post-listing stage and lock-in calendar. DB-only. */
    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(defaultValue = "36") int months) {
        return trackingService.recentView(months);
    }

    /** One issue, everything on record. DB-only; 404 when not tracked. */
    @GetMapping("/issue")
    public ResponseEntity<Map<String, Object>> issue(@RequestParam String symbol) {
        return trackingService.issueView(symbol)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Run the capture now: three NSE list calls, a detail call per pipeline issue, a bounded
     * backfill, and one Kite quote per listing under a year old. Otherwise runs at 12:15.
     */
    @PostMapping("/capture")
    public IpoTrackingService.CaptureResult capture() {
        requireOutsideCrunch("IPO capture");
        log.info("API: manual IPO capture requested");
        return trackingService.capture(this::insideAfternoonCrunch);
    }

    /**
     * Analyse one listed issue now: candles since listing, NSE results and shareholding, and —
     * past the six-month window — a compute-to-decide composite (Gotcha 50). 5-20 s.
     */
    @PostMapping("/analyse")
    public ResponseEntity<Map<String, Object>> analyse(@RequestParam String symbol) {
        requireOutsideCrunch("IPO analysis");
        log.info("API: IPO post-listing analysis requested for {}", symbol);
        try {
            IpoIssueEntity e = trackingService.analyse(symbol);
            return trackingService.issueView(e.getSymbol()).map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(reason(404, "Not found", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(reason(422, "Not listed", ex.getMessage()));
        }
    }

    // ------------------------------------------------------------------ guards

    private void requireOutsideCrunch(String what) {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        LocalTime t = now.toLocalTime();
        boolean nseCrunch = !t.isBefore(NSE_CRUNCH_START) && !t.isAfter(NSE_CRUNCH_END);
        if (nseCrunch && marketHoursService.isMarketOpen()) {
            throw new CrunchException(String.format("%s makes live NSE calls and is blocked between %s and %s IST: "
                    + "the FII/DII fetch at 09:45 and its report at 10:00 share the same NSE session. Try again "
                    + "after %s.", what, NSE_CRUNCH_START, NSE_CRUNCH_END, NSE_CRUNCH_END));
        }
        if (insideAfternoonCrunch()) {
            throw new CrunchException(String.format("%s makes live NSE and Kite calls and is blocked from %s to the "
                    + "close: the 14:00 screening, the 14:45 insider capture and the 15:05-15:28 report jobs need "
                    + "them, and those jobs abort silently at 15:30. Try again after the close, or before %s IST.",
                    what, AFTERNOON_CRUNCH_START, AFTERNOON_CRUNCH_START));
        }
    }

    private boolean insideAfternoonCrunch() {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        return marketHoursService.isMarketOpen() && !now.toLocalTime().isBefore(AFTERNOON_CRUNCH_START);
    }

    /** Refusal carrying its own reason — a bare 409 hides the explanation the guard exists to give. */
    static class CrunchException extends RuntimeException {
        CrunchException(String message) { super(message); }
    }

    @ExceptionHandler(CrunchException.class)
    public ResponseEntity<Map<String, Object>> handleCrunch(CrunchException e) {
        log.info("IPO request refused: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(reason(409, "Blocked", e.getMessage()));
    }

    private static Map<String, Object> reason(int status, String error, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", error);
        body.put("reason", reason);
        return body;
    }
}
