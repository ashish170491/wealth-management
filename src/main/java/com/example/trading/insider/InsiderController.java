package com.example.trading.insider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Insider disclosure endpoints (SPEC §28, F1).
 *
 * <p>The two GETs are DB-only and cheap — safe for a dashboard page-load. The POST performs
 * live NSE fetches and must never be wired into one (SPEC §27.4 / Gotcha 17).
 */
@Slf4j
@RestController
@RequestMapping("/api/insider")
@RequiredArgsConstructor
public class InsiderController {

    private final InsiderDisclosureRepository repository;
    private final InsiderPulseService pulseService;
    private final InsiderCaptureScheduler captureScheduler;
    private final com.example.trading.persistence.MultibaggerScoreRepository scoreRepository;

    /**
     * Disclosure history plus the rolling pulse for one stock. <b>DB-only</b> — and now
     * actually so (B-039).
     *
     * <p>This previously called {@code StockValuationService.getValuationData()} to size the
     * pulse against market cap, which on a cold cache means an XBRL fetch plus a Kite quote.
     * The cache is 30 minutes and this app restarts daily, so in practice every first call of
     * the day paid that cost behind a Javadoc promising it would not — exactly the trap
     * Gotcha 17 exists to prevent, waiting for whoever wired it into a page load.
     *
     * <p>Market cap now comes from the most recent screening row. It is at most a day stale,
     * which is immaterial for a percent-of-market-cap figure, and null when the stock has
     * never been screened — in which case the pulse reports event counts without a size
     * verdict rather than inventing one.
     */
    @GetMapping("/{symbol}")
    public Map<String, Object> forSymbol(@PathVariable String symbol) {
        String qualified = symbol.contains(":") ? symbol : "NSE:" + symbol;
        Double marketCap = scoreRepository.findHistoryBySymbol(qualified).stream()
                .map(com.example.trading.persistence.MultibaggerScoreEntity::getMarketCapCrores)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", qualified);
        out.put("pulse", pulseService.computePulse(qualified, marketCap));
        out.put("disclosures", repository.findBySymbolOrderByTransactionDateDesc(qualified));
        return out;
    }

    /** All-market disclosures captured in the last N days. DB-only. */
    @GetMapping("/recent")
    public List<InsiderDisclosureEntity> recent(@RequestParam(defaultValue = "30") int days) {
        return repository.findAllSince(LocalDate.now().minusDays(Math.max(1, days)));
    }

    /**
     * Manually run a capture pass. Hits NSE live — never call this from a page load.
     */
    @PostMapping("/capture")
    public InsiderCaptureScheduler.CaptureResult capture() {
        return captureScheduler.runCapture();
    }
}
