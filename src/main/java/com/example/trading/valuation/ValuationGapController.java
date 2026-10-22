package com.example.trading.valuation;

import com.example.trading.scheduler.MarketHoursService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Valuation gap and implied growth (SPEC §57.7). Namespace {@code /api/valuation-gap}.
 *
 * <p>The four GETs are <b>database or classpath only</b> and safe on a page load: no NSE call, no
 * Kite call, no model call. The two POSTs write or cost something, so no page load can reach them.
 * {@code symbol} is a query parameter throughout - symbols carry a colon.
 *
 * <p>Every refusal carries its reason in the body, because {@code server.error.include-message} is
 * {@code never} and a bare status would be a guard whose explanation never reaches the person it is
 * guarding against (B-049).
 *
 * <p><b>Who may press the manual capture.</b> This app has no authentication layer - it is a single
 * investor's tool on their own machine, and none of its other POST endpoints is authenticated either.
 * Introducing one here would be a scope of its own, so {@code POST /snapshot} is restricted to the
 * <b>loopback interface</b>: a request from another host is refused with 403. That is the strongest
 * restriction the application can express today, and it is stated rather than implied.
 */
@Slf4j
@RestController
@RequestMapping("/api/valuation-gap")
@RequiredArgsConstructor
public class ValuationGapController {

    private final ValuationGapService service;
    private final ValuationGapSnapshotService snapshotService;
    private final ValuationAssumptionChallenger challenger;
    private final ValuationGapConfig config;
    private final MarketHoursService marketHoursService;

    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9:&_.\\- ]{1,40}");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    // ---------------------------------------------------------------- reads (page-load safe)

    /** One stock's reading. 200 always, except 404 when the stock is in no table under any spelling. */
    @GetMapping
    public ResponseEntity<?> reading(@RequestParam String symbol) {
        String bad = invalidSymbol(symbol);
        if (bad != null) return reason(HttpStatus.BAD_REQUEST, bad);
        Optional<ValuationReading> reading = service.read(symbol.trim(), today());
        if (reading.isEmpty()) {
            return reason(HttpStatus.NOT_FOUND, "No screening row and no annual accounts are on file for "
                    + symbol.trim() + " under any exchange spelling. This is the app not knowing the stock, "
                    + "which is a different fact from the stock being refused.");
        }
        ValuationReading r = reading.get();
        return ResponseEntity.ok(r.toBuilder().snapshots(snapshotService.summary(r.symbol())).build());
    }

    /** The rule table, the fingerprint and the reference-multiple limitations. No I/O at all. */
    @GetMapping("/rules")
    public Map<String, Object> rules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleset", ValuationGapRules.RULESET);
        m.put("fingerprint", ValuationGapRules.fingerprint());
        m.put("rules", ValuationGapRules.rules());
        m.put("referenceMultipleLimits", ValuationGapRules.MULTIPLES_LIMITS);
        m.put("statedHorizon", "FY" + FiscalHorizon.REFERENCE_FISCAL_YEAR + " (year ending "
                + FiscalHorizon.REFERENCE_YEAR_ENDS + ")");
        m.put("note", "Constants marked provisional are starting values awaiting a calibration report, "
                + "not findings. A change to any of them changes the fingerprint and starts a new rule-set.");
        return m;
    }

    /** How many stocks the lens can read, by the first gate each failed, plus the snapshot record's own counts. */
    @GetMapping("/coverage")
    public ResponseEntity<?> coverage() {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<ValuationGapService.Coverage> c = service.coverage(today());
        m.put("funnel", c.orElse(null));
        if (c.isEmpty()) {
            m.put("funnelNote", "No complete screening run in the last 14 days, so there is no funnel to count.");
        }
        m.put("snapshots", snapshotService.coverageBlock());
        return ResponseEntity.ok(m);
    }

    /** The stored validation rows for one stock, newest first. 404 when there are none. */
    @GetMapping("/snapshots")
    public ResponseEntity<?> snapshots(@RequestParam String symbol) {
        String bad = invalidSymbol(symbol);
        if (bad != null) return reason(HttpStatus.BAD_REQUEST, bad);
        var rows = snapshotService.forSymbol(symbol.trim());
        if (rows.isEmpty()) {
            return reason(HttpStatus.NOT_FOUND, "No validation snapshot is stored for " + symbol.trim()
                    + " yet. Rows are written only when a stock's inputs change, by the 12:35 weekday job.");
        }
        return ResponseEntity.ok(rows);
    }

    // ------------------------------------------------------------------------------ writes / cost

    /**
     * Capture the snapshot now. Idempotent: unchanged inputs write nothing, so pressing it twice is
     * safe. It runs the same method as the 12:35 job, through the same gates.
     */
    @PostMapping("/snapshot")
    public ResponseEntity<?> snapshot(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!LOOPBACK.contains(remote)) {
            log.warn("Valuation-gap manual snapshot refused for non-loopback caller {}", remote);
            return reason(HttpStatus.FORBIDDEN, "The manual snapshot can be started only from the machine the "
                    + "app runs on. This app has no sign-in, so access is limited to the local interface.");
        }
        if (!config.isSnapshotEnabled()) {
            return reason(HttpStatus.CONFLICT, "Snapshot capture is switched off "
                    + "(valuation-gap.snapshot-enabled is false).");
        }
        ValuationGapSnapshotService.CaptureResult result =
                snapshotService.captureNow(today(), ValuationGapSnapshotService.MANUAL);
        if (result.skipped()) return reason(HttpStatus.CONFLICT, result.skipReason());
        return ResponseEntity.ok(result);
    }

    /** One click, behind a cost-and-purpose confirmation on the page. Never throws, never writes. */
    @PostMapping("/challenge")
    public ResponseEntity<?> challenge(@RequestParam String symbol) {
        String bad = invalidSymbol(symbol);
        if (bad != null) return reason(HttpStatus.BAD_REQUEST, bad);
        Optional<ValuationReading> reading = service.read(symbol.trim(), today());
        if (reading.isEmpty()) {
            return ResponseEntity.ok(new ValuationAssumptionChallenger.Result(
                    ValuationAssumptionChallenger.Status.READING_UNAVAILABLE, java.util.List.of(), null,
                    "The stock is in no table, so there is no reading to question."));
        }
        return ResponseEntity.ok(challenger.challenge(reading.get()));
    }

    // ----------------------------------------------------------------------------------- helpers

    private LocalDate today() {
        return LocalDate.now(marketHoursService.getMarketZone());
    }

    static String invalidSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return "symbol is required, for example NSE:RELIANCE.";
        if (!SYMBOL.matcher(symbol.trim()).matches()) {
            return "symbol may contain letters, digits and : & _ . - only, up to 40 characters.";
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> reason(HttpStatus status, String why) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("reason", why);
        return ResponseEntity.status(status).body(body);
    }
}
