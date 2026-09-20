package com.example.trading.funds;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mutual fund record (SPEC §52, §16).
 *
 * <p><b>The namespace is {@code /api/mf}, not {@code /api/funds}.</b> Both were free, but
 * {@code /api/funds} sits three characters from the existing {@code /api/fundamentals} — annual
 * accounts, forensics and turnarounds — and two unrelated features whose paths differ by a suffix
 * is Gotcha 118's hazard pointed at whoever maintains this next. {@code /api/mf} also matches the
 * broker's own namespace and the {@code mf_*} table prefix.
 *
 * <p>The two GETs are <b>DB-only and page-load safe</b> (§20 rule 7). The POSTs make live calls and
 * are refused inside the windows other jobs need, <b>with the reason in the body</b>:
 * {@code server.error.include-message} is {@code never}, so a bare {@code ResponseStatusException}
 * would produce a 409 whose explanation never reaches the caller — which is the same silent failure
 * the guard exists to prevent (B-049).
 *
 * <p>Nothing here contributes a point to any score, and there is no flag that would let it.
 */
@Slf4j
@RestController
@RequestMapping("/api/mf")
@RequiredArgsConstructor
public class MutualFundController {

    /** The AMFI pull is not a broker call, but it is a multi-second parse plus thousands of
     *  upserts on the 4-thread pool that has starved the afternoon twice (B-014, B-049). */
    private static final LocalTime NAV_REFRESH_BLOCKED_FROM = LocalTime.of(14, 0);

    /** One paced broker call - the same rule as every per-row refresh in the app. */
    private static final LocalTime BROKER_BLOCKED_FROM = LocalTime.of(14, 55);

    private final MarketHoursService marketHoursService;
    private final MutualFundViewService viewService;
    private final MutualFundNavSyncService navSyncService;
    private final MutualFundHoldingsService holdingsService;

    // ------------------------------------------------------------------ reads (DB-only)

    @GetMapping("/holdings")
    public Map<String, Object> holdings() {
        return viewService.holdings();
    }

    @GetMapping("/scheme")
    public ResponseEntity<Map<String, Object>> scheme(@RequestParam long code) {
        return viewService.scheme(code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(reason(404, "Not tracked",
                                "Scheme " + code + " is not in mf_scheme. That means this app has "
                                        + "never persisted it, not that the scheme does not exist.")));
    }

    @GetMapping("/coverage")
    public Map<String, Object> coverage() {
        return viewService.coverage();
    }

    // ------------------------------------------------------------------ writes (button-only)

    @PostMapping("/nav/refresh")
    public Map<String, Object> refreshNav() {
        requireBefore(NAV_REFRESH_BLOCKED_FROM,
                "The AMFI NAV pull",
                "it parses a 1.5 MB file and writes thousands of rows on the shared scheduler pool, "
                        + "and the 14:00 screening plus the 15:05-15:28 report jobs abort silently at 15:30");

        MutualFundNavSyncService.SyncResult r = navSyncService.sync();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("succeeded", r.succeeded());
        out.put("pulledAt", r.pulledAt());
        out.put("navDateInFile", r.navDateInFile());
        out.put("rowsParsed", r.rowsParsed());
        out.put("schemesWritten", r.schemesWritten());
        out.put("navRowsWritten", r.navRowsWritten());
        out.put("unparsedLines", r.unparsedLines());
        out.put("unclassifiedHeaders", r.unclassifiedHeaders());
        out.put("failureReason", r.failureReason());
        return out;
    }

    @PostMapping("/holdings/sync")
    public Map<String, Object> syncHoldings() {
        requireBefore(BROKER_BLOCKED_FROM,
                "The mutual fund holdings sync",
                "it makes a paced broker call, and the broker budget is process-wide at ~2.9 req/s "
                        + "with the afternoon jobs already competing for it");

        MutualFundHoldingsService.SyncResult r = holdingsService.sync();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fetched", r.fetched());
        out.put("upserted", r.upserted());
        out.put("markedExited", r.exited());
        out.put("unresolved", r.unresolved());
        out.put("note", r.note());
        if (r.unresolved() > 0) {
            out.put("unresolvedNote", r.unresolved() + " holding(s) could not be matched to an AMFI "
                    + "scheme by ISIN. They are kept and reported, never guessed or dropped.");
        }
        return out;
    }

    // ------------------------------------------------------------------ guards

    private void requireBefore(LocalTime blockedFrom, String what, String why) {
        if (!marketHoursService.isMarketOpen()) {
            return; // Outside market hours nothing is competing for anything.
        }
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        if (!now.toLocalTime().isBefore(blockedFrom)) {
            throw new CrunchException(String.format(
                    "%s is blocked from %s IST to the close on a trading day, because %s. "
                            + "Try again after the close, or before %s IST.",
                    what, blockedFrom, why, blockedFrom));
        }
    }

    /** Refusal carrying its own reason - a bare 409 hides the explanation the guard exists to give. */
    static class CrunchException extends RuntimeException {
        CrunchException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(CrunchException.class)
    public ResponseEntity<Map<String, Object>> handleCrunch(CrunchException e) {
        log.info("Mutual fund request refused: {}", e.getMessage());
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
