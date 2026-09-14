package com.example.trading.portfolio.risk;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Diversification-risk endpoint. See SPEC.md §7 and §16. */
@RestController
@RequestMapping("/api/portfolio/risk")
@RequiredArgsConstructor
public class DiversificationController {

    private final DiversificationService service;

    @GetMapping
    public ResponseEntity<DiversificationDto.RiskResponse> getRisk() {
        return ResponseEntity.ok(service.computeRisk());
    }
}
