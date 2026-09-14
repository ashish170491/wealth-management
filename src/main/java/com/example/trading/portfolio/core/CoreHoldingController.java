package com.example.trading.portfolio.core;

import com.example.trading.portfolio.conviction.HoldingConvictionEntity;
import com.example.trading.portfolio.conviction.HoldingConvictionRepository;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core-holding endpoints (SPEC §35.7, §16).
 *
 * <p>The two GETs are DB-only and safe on a dashboard page load. The two POSTs are not: classifying
 * costs two paced Kite calls per holding, and this API has form (a GET here can email a report or
 * start a 30-minute scan — CLAUDE.md Gotcha 17), so the compute trigger is deliberately a POST that
 * no page load can reach.
 *
 * <p>{@code symbol} is a query parameter throughout. Symbols contain a colon
 * ({@code NSE:RELIANCE}), which is legal but hazardous as a path variable across Tomcat, Spring and
 * {@code fetch()}.
 */
@RestController
@RequestMapping("/api/portfolio/core-holdings")
@Slf4j
@RequiredArgsConstructor
public class CoreHoldingController {

    private final CoreClassificationService classificationService;
    private final CoreOverlayService overlayService;
    private final HoldingConvictionRepository convictionRepository;
    private final MarketHoursService marketHoursService;
    private final CoreHoldingConfig config;

    /**
     * A ~25-second Kite sweep may not start once the afternoon jobs need the shared rate limit.
     * Kite is paced process-wide at ~2.9 req/s (B-027) and the 15:05-15:28 report jobs abort
     * silently at 15:30 (B-014), so a manual run started at 15:20 lands its damage on a report the
     * user was expecting rather than on the request that caused it.
     */
    private static final LocalTime CRUNCH_START = LocalTime.of(14, 55);

    /** Latest classification per holding, from the newest date that has rows. DB-only. */
    @GetMapping
    public Map<String, Object> latest() {
        List<CoreDto.CoreHoldingView> views = classificationService.latestClassifications();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", config.isEnabled());
        body.put("suppressTechnicalExits", config.isSuppressTechnicalExits());
        body.put("mode", config.isSuppressTechnicalExits() ? "SUPPRESSION" : "OBSERVATION");
        body.put("count", views.size());
        body.put("holdings", views);
        return body;
    }

    /** Tier and durability over time for one stock. DB-only. */
    @GetMapping("/history")
    public List<CoreDto.CoreHoldingView> history(@RequestParam String symbol,
                                                 @RequestParam(defaultValue = "180") int days) {
        return classificationService.history(symbol, days);
    }

    /**
     * The investor's judgement overrides the gates (SPEC §6.2 audit line). Recorded with a
     * timestamp and a note; it changes the tier and hides nothing — a forensic flag still shows in
     * red in the same email.
     */
    @PostMapping("/override")
    public ResponseEntity<Map<String, Object>> override(@RequestBody Map<String, String> request) {
        String symbol = request.get("symbol");
        String override = request.get("override");
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol is required"));
        }
        if (override != null && !override.isBlank()
                && !"FORCE_CORE".equals(override) && !"FORCE_SATELLITE".equals(override)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "override must be FORCE_CORE, FORCE_SATELLITE, or omitted to clear"));
        }
        HoldingConvictionEntity conviction = convictionRepository.findBySymbol(symbol).orElse(null);
        if (conviction == null) {
            // The override lives on the conviction record because that is where the investor's own
            // reasoning already lives. Inventing a blank one here would fabricate a thesis.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "No conviction record for " + symbol
                            + ". Create one first (POST /api/portfolio/conviction) - the override "
                            + "belongs beside the thesis it overrides."));
        }
        conviction.setCoreOverride(override == null || override.isBlank() ? null : override);
        conviction.setCoreOverrideNote(request.get("note"));
        conviction.setCoreOverrideAt(LocalDateTime.now());
        convictionRepository.save(conviction);
        overlayService.invalidate();

        log.info("Core override set for {}: {} ({})", symbol, override, request.get("note"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("override", conviction.getCoreOverride());
        body.put("note", conviction.getCoreOverrideNote());
        body.put("appliedAt", conviction.getCoreOverrideAt());
        body.put("takesEffect", "the next classification run (10:30 IST) or POST /classify now");
        return ResponseEntity.ok(body);
    }

    /** Recompute now. Costs two paced Kite calls per holding; refused in the afternoon crunch. */
    @PostMapping("/classify")
    public Map<String, Object> classify() {
        requireOutsideAfternoonCrunch();
        List<CoreDto.CoreHoldingView> views = classificationService.classifyAll();
        overlayService.invalidate();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("classified", views.size());
        body.put("holdings", views);
        return body;
    }

    private void requireOutsideAfternoonCrunch() {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        if (now.toLocalTime().isBefore(CRUNCH_START) || !marketHoursService.isMarketOpen()) return;

        throw new ClassifyBlockedException(String.format(
                "Core classification makes about two Kite calls per holding and is blocked between "
                        + "%s and market close: the 15:05-15:28 report jobs need the shared broker "
                        + "rate limit and abort silently at 15:30. Try again after the close, or "
                        + "before %s IST.", CRUNCH_START, CRUNCH_START));
    }

    /**
     * Refusal carrying its own reason. Not {@code ResponseStatusException}:
     * {@code server.error.include-message} defaults to {@code never}, so the caller would receive a
     * bare 409 and a guard whose explanation never arrives is the silent failure it exists to
     * prevent (B-049).
     */
    static class ClassifyBlockedException extends RuntimeException {
        ClassifyBlockedException(String message) { super(message); }
    }

    @ExceptionHandler(ClassifyBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleBlocked(ClassifyBlockedException e) {
        log.info("Core classification refused: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 409);
        body.put("error", "Blocked");
        body.put("reason", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
