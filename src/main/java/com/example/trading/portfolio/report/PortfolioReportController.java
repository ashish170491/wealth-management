package com.example.trading.portfolio.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Manual trigger for the consolidated portfolio-snapshot email. See SPEC.md §14.
 */
@RestController
@RequestMapping("/api/portfolio/reports")
@RequiredArgsConstructor
@Slf4j
public class PortfolioReportController {

    private final PortfolioSnapshotReportService service;

    @PostMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> sendSnapshot() {
        try {
            String subject = service.sendSnapshot();
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "subject", subject,
                    "timestamp", LocalDateTime.now().toString()));
        } catch (Exception e) {
            log.error("PortfolioSnapshot: failed to send: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "FAILED",
                    "error", e.getMessage()));
        }
    }
}
