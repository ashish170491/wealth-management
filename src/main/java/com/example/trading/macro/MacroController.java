package com.example.trading.macro;

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

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Macro and geopolitical event exposure (SPEC §48.8).
 *
 * <p>The five GETs are database or classpath only and safe on page load. The three POSTs write, and
 * {@code /ingest} also makes live calls, so it is refused inside the windows other jobs need - with
 * the reason in the body, because {@code server.error.include-message} is {@code never} and a bare
 * 409 would be a guard whose explanation never reaches the person it is guarding against (B-049).
 *
 * <p>{@code symbol} is a query parameter throughout - symbols carry a colon.
 */
@Slf4j
@RestController
@RequestMapping("/api/macro")
@RequiredArgsConstructor
public class MacroController {

    private final MacroViewService viewService;
    private final MacroIngestService ingestService;
    private final MacroEventRepository eventRepository;
    private final MacroConfig config;
    private final MarketHoursService marketHoursService;

    /** The FII/DII fetch at 09:45 and its report at 10:00 share the single NSE session. */
    private static final LocalTime NSE_CRUNCH_START = LocalTime.of(9, 40);
    private static final LocalTime NSE_CRUNCH_END = LocalTime.of(10, 15);

    // ------------------------------------------------------------------ reads (page-load safe)

    /** Events in the window with the holdings and watchlist stocks each one touches. DB-only. */
    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "0") int days,
                                      @RequestParam(defaultValue = "false") boolean includeDismissed) {
        return viewService.eventsView(days, includeDismissed);
    }

    /**
     * One stock's reading.
     *
     * <p>404 means the app has never classified this business, so it cannot look it up in the map;
     * a 200 carrying {@code NOT_MEASURED} means it has, and the map has no rule for it. Those are
     * different facts and the screens render them differently (SPEC §21 rule 7).
     */
    @GetMapping("/exposure")
    public ResponseEntity<Map<String, Object>> exposure(@RequestParam String symbol) {
        Optional<Map<String, Object>> view = viewService.exposureView(symbol);
        return view.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(reason(404, "Never screened",
                        "This stock is not in the screening universe, so the app has not classified its "
                                + "business and cannot look it up in the exposure map.")));
    }

    /** Every active holding with its reading, and how much of the portfolio could be measured. DB-only. */
    @GetMapping("/exposure/portfolio")
    public Map<String, Object> portfolio() {
        return viewService.portfolioView();
    }

    /** Dated events in the look-ahead window. Classpath resource plus one holdings read. */
    @GetMapping("/calendar")
    public Map<String, Object> calendar(@RequestParam(defaultValue = "0") int days) {
        return viewService.calendarView(days);
    }

    /** The rule table itself, so the rules can be read rather than trusted. Classpath only. */
    @GetMapping("/map")
    public ResponseEntity<Map<String, Object>> map(@RequestParam(required = false) String factor) {
        if (factor != null && !factor.isBlank()) {
            Optional<MacroFactor> f = MacroFactor.parse(factor);
            if (f.isEmpty()) {
                return ResponseEntity.badRequest().body(reason(400, "Unknown factor",
                        "\"" + factor + "\" is not a factor this app tracks. Known factors: "
                                + java.util.Arrays.toString(MacroFactor.values())));
            }
            return ResponseEntity.ok(viewService.mapView(f.get()));
        }
        return ResponseEntity.ok(viewService.mapView(null));
    }

    /** What an ingest would do right now, what it costs, and when it last ran. DB-only. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return viewService.statusView();
    }

    // ------------------------------------------------------------------ writes

    /**
     * Read the news feeds now and extract events.
     *
     * <p>The one control on the Events page that leaves the machine. Refused inside the windows the
     * scheduled jobs need, with the reason in the body.
     */
    @PostMapping("/ingest")
    public MacroIngestService.IngestResult ingest() {
        requireOutsideCrunch("Reading the news feeds");
        log.info("API: manual macro ingest requested");
        return ingestService.ingest();
    }

    /** Run the feed scan alone, without extracting anything. Cheap; replaces the old news trigger. */
    @PostMapping("/news/scan")
    public Map<String, Object> scan() {
        requireOutsideCrunch("Scanning the news feeds");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headlinesStored", ingestService.scanOnly());
        out.put("note", "Headlines were fetched and stored. Nothing was extracted - use the ingest "
                + "endpoint, or the button on the Events page, to read them.");
        return out;
    }

    /**
     * Mark an event as noise.
     *
     * <p>It stops counting against every stock and stays on the record. A ledger that quietly
     * deleted what turned out to be wrong could not be judged later (SPEC §25.1), and readings
     * already filed for measurement are deliberately left alone - they were made in good faith on
     * the day and the accuracy record has to include them.
     */
    @PostMapping("/events/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@RequestParam long id) {
        return eventRepository.findById(id)
                .map(e -> {
                    e.setDismissed(true);
                    e.setDismissedAt(LocalDateTime.now());
                    eventRepository.save(e);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("id", id);
                    body.put("dismissed", true);
                    body.put("note", "Recorded as noise. It stops counting against your stocks and "
                            + "stays on the record.");
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(reason(404, "Not found", "No event with id " + id + ".")));
    }

    // ------------------------------------------------------------------ guards

    private void requireOutsideCrunch(String what) {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        LocalTime t = now.toLocalTime();
        boolean marketOpen = marketHoursService.isMarketOpen();

        boolean nseCrunch = !t.isBefore(NSE_CRUNCH_START) && !t.isAfter(NSE_CRUNCH_END);
        if (nseCrunch && marketOpen) {
            throw new CrunchException(String.format("%s makes live calls and is blocked between %s and %s "
                            + "IST: the FII/DII fetch at 09:45 and its report at 10:00 share the same "
                            + "network session. Try again after %s.",
                    what, NSE_CRUNCH_START, NSE_CRUNCH_END, NSE_CRUNCH_END));
        }

        LocalTime refuseFrom = config.getIngest().refuseFromTime();
        if (marketOpen && !t.isBefore(refuseFrom)) {
            throw new CrunchException(String.format("%s is blocked from %s to the close: the 14:00 "
                            + "screening, the 14:45 insider capture and the 15:05-15:28 report jobs need "
                            + "the network and the broker, and those jobs abort silently at 15:30. Try "
                            + "again after the close, or before %s IST.",
                    what, refuseFrom, refuseFrom));
        }
    }

    /** A refusal carrying its own reason - a bare 409 hides the explanation the guard exists to give. */
    static class CrunchException extends RuntimeException {
        CrunchException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(CrunchException.class)
    public ResponseEntity<Map<String, Object>> handleCrunch(CrunchException e) {
        log.info("Macro request refused: {}", e.getMessage());
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
