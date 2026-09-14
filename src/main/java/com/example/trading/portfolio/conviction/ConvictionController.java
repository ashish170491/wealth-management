package com.example.trading.portfolio.conviction;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Conviction & Thesis Tracking endpoints (SPEC.md §6 and §16). */
@RestController
@RequestMapping("/api/portfolio/conviction")
@RequiredArgsConstructor
public class ConvictionController {

    private final ConvictionService service;

    /** GET /api/portfolio/conviction — report across all holdings with conviction records. */
    @GetMapping
    public ResponseEntity<ConvictionDto.ConvictionReport> getReport() {
        return ResponseEntity.ok(service.getReport());
    }

    /** GET /api/portfolio/conviction/{symbol} — drift view for a single holding. */
    @GetMapping("/{symbol}")
    public ResponseEntity<ConvictionDto.ConvictionDrift> getOne(@PathVariable String symbol) {
        return ResponseEntity.ok(service.getDrift(symbol));
    }

    /** PUT /api/portfolio/conviction — upsert (symbol used as key). */
    @PutMapping
    public ResponseEntity<HoldingConvictionEntity> upsert(@RequestBody ConvictionDto.UpsertRequest req) {
        return ResponseEntity.ok(service.upsert(req));
    }

    /** PUT /api/portfolio/conviction/bulk — upsert many thesis records in one call. */
    @PutMapping("/bulk")
    public ResponseEntity<java.util.Map<String, Object>> bulk(
            @RequestBody java.util.List<ConvictionDto.UpsertRequest> requests) {
        return ResponseEntity.ok(service.bulkUpsert(requests));
    }

    /** POST /api/portfolio/conviction/auto-seed — generate thesis for every holding from app's discovery signals. */
    @org.springframework.web.bind.annotation.PostMapping("/auto-seed")
    public ResponseEntity<java.util.Map<String, Object>> autoSeed() {
        return ResponseEntity.ok(service.autoSeedFromDiscovery());
    }
}
