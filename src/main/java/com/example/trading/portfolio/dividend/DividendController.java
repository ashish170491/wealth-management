package com.example.trading.portfolio.dividend;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

/** Dividend tracking endpoints (SPEC.md §11 and §16). */
@RestController
@RequestMapping("/api/portfolio/dividends")
@RequiredArgsConstructor
public class DividendController {

    private final DividendService service;

    @GetMapping
    public ResponseEntity<List<DividendDto.DividendView>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<DividendEventEntity> record(@RequestBody DividendDto.CreateEventRequest req) {
        return ResponseEntity.ok(service.record(req));
    }

    /** POST /api/portfolio/dividends/{id}/received?amount= - the cash landed. Click-only from the UI. */
    @PostMapping("/{id}/received")
    public ResponseEntity<DividendDto.DividendView> received(@PathVariable Long id,
                                                             @RequestParam(required = false) Double amount) {
        return ResponseEntity.ok(service.markReceived(id, amount));
    }

    @GetMapping("/symbol/{symbol}")
    public ResponseEntity<List<DividendDto.DividendView>> listForSymbol(@PathVariable String symbol) {
        return ResponseEntity.ok(service.listForSymbol(symbol));
    }

    /** GET /api/portfolio/dividends/summary — current Indian fiscal year summary. */
    @GetMapping("/summary")
    public ResponseEntity<DividendDto.AnnualSummary> summary() {
        LocalDate today = LocalDate.now();
        int fy = today.getMonth().getValue() >= Month.APRIL.getValue() ? today.getYear() : today.getYear() - 1;
        return ResponseEntity.ok(service.annualSummary(fy));
    }

    @GetMapping("/reinvest")
    public ResponseEntity<List<DividendDto.ReinvestmentSuggestion>> reinvest() {
        return ResponseEntity.ok(service.reinvestmentSuggestions());
    }
}
