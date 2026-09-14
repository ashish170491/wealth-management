package com.example.trading.portfolio.rebalance;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rebalancing-engine endpoint (SPEC.md §10 and §16). */
@RestController
@RequestMapping("/api/portfolio/rebalance")
@RequiredArgsConstructor
public class RebalanceController {

    private final RebalanceService service;

    @PostMapping
    public ResponseEntity<RebalanceDto.RebalanceProposal> propose() {
        return ResponseEntity.ok(service.generateProposal());
    }

    /** Alias so the user can preview via GET without side effects. */
    @GetMapping
    public ResponseEntity<RebalanceDto.RebalanceProposal> preview() {
        return ResponseEntity.ok(service.generateProposal());
    }
}
