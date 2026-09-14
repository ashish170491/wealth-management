package com.example.trading.alerts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual trigger for the daily target-hit scan (SPEC.md §26). The scheduled run
 * is 15:05 IST MON-FRI; this endpoint lets you run it on demand (e.g. to test or
 * to catch up after a missed session).
 */
@RestController
@RequestMapping("/api/alerts")
@Slf4j
@RequiredArgsConstructor
public class TargetHitController {

    private final TargetHitAlertService targetHitAlertService;

    @PostMapping("/target-hits/scan")
    public Map<String, Object> scan() {
        int n = targetHitAlertService.scanAndAlert();
        return Map.of(
                "status", "ok",
                "newlyHit", n,
                "emailSent", n > 0);
    }

    /**
     * Send a sample target-hit email (synthetic data) to verify rendering +
     * delivery. Writes nothing to the DB. Subject is prefixed [TEST].
     */
    @PostMapping("/target-hits/preview")
    public Map<String, Object> preview() {
        targetHitAlertService.sendSampleEmail();
        return Map.of(
                "status", "ok",
                "message", "Sample target-hit email sent to the configured inbox (subject prefixed [TEST])");
    }
}
