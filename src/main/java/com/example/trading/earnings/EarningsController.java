package com.example.trading.earnings;

import com.example.trading.holdings.SymbolVariants;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quarterly result endpoints (SPEC.md §50.6).
 *
 * <p>The GETs are DB-only and safe on a page load. The one POST makes live NSE calls and is
 * refused, with the reason in the body, inside the windows other jobs need — a thrown status
 * alone arrives bare, because {@code server.error.include-message} is {@code never} (B-049).
 *
 * <p><b>Nothing here changes a score, a weight or a signal.</b> The vocabulary
 * ({@code STRONG / IN_LINE / WEAK / CONCERNING / NOT_MEASURED}) contains no instruction to
 * transact, by design and by test (SPEC §19, §20 rule 10).
 *
 * <p>{@code symbol} is a query parameter throughout — symbols carry a colon, which is legal but
 * hazardous in a path across Tomcat, Spring and {@code fetch()}.
 */
@Slf4j
@RestController
@RequestMapping("/api/earnings")
@RequiredArgsConstructor
public class EarningsController {

    private final QuarterlyResultService service;
    private final HoldingsRepository holdingsRepository;
    private final MarketHoursService marketHoursService;
    private final EarningsConfig config;

    /** The FII/DII fetch at 09:45 and its report at 10:00 share the single NSE session. */
    private static final LocalTime NSE_CRUNCH_START = LocalTime.of(9, 40);
    private static final LocalTime NSE_CRUNCH_END = LocalTime.of(10, 15);
    /** From the 14:00 screening to the close, NSE and the broker are spoken for. */
    private static final LocalTime AFTERNOON_CRUNCH_START = LocalTime.of(14, 0);

    // ------------------------------------------------------------------ reads (DB-only)

    /**
     * One company: the latest quarter read against its history, every filed quarter on record, and
     * when the next one is expected.
     *
     * <p>Returns 200 with a {@code NOT_MEASURED} reading rather than 404 when nothing has been
     * captured — "not captured yet" and "reported badly" must never render alike (Gotcha 44).
     */
    @GetMapping("/result")
    public Map<String, Object> result(@RequestParam String symbol) {
        return view(symbol, service.forSymbol(symbol));
    }

    /**
     * Every active holding with its latest result read, plus the coverage line.
     *
     * <p>The coverage figure is mandatory, not decorative: an empty-looking column here reads as
     * "no holding reported anything worrying", when it may mean "nothing was captured".
     */
    @GetMapping("/portfolio")
    public Map<String, Object> portfolio() {
        List<HoldingsEntity> holdings = holdingsRepository.findActive();
        List<String> symbols = holdings.stream().map(HoldingsEntity::getSymbol).toList();
        Map<String, QuarterlyResultService.Reading> readings = service.forSymbols(symbols);

        List<Map<String, Object>> rows = new ArrayList<>();
        int measured = 0;
        int attention = 0;
        for (HoldingsEntity h : holdings) {
            QuarterlyResultService.Reading r = readings.get(h.getSymbol());
            if (r == null) continue;
            if (r.result().measured()) measured++;
            if (r.result().needsAttention()) attention++;
            Map<String, Object> row = view(h.getSymbol(), r);
            row.put("quantity", h.getQuantity());
            row.put("pnlPercent", h.getPnlPercent());
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("holdings", rows);
        out.put("measured", measured);
        out.put("total", holdings.size());
        out.put("needingAttention", attention);
        out.put("coverageNote", String.format(
                "A quarterly result has been captured and judged for %d of %d holdings. A holding "
                        + "reading \"not measured\" has no filed quarter on record here — that is a "
                        + "gap in what the app has collected, never a statement that the company "
                        + "did not report. None of this changes any score.",
                measured, holdings.size()));
        return out;
    }

    /** Results published across the whole book in the last {@code days} days. DB-only. */
    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(defaultValue = "30") int days) {
        List<QuarterlyResultEntity> rows = service.recentlyPublished(days);
        List<Map<String, Object>> out = new ArrayList<>();
        for (QuarterlyResultEntity q : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", q.getSymbol());
            m.put("quarterEnd", q.getQuarterEnd());
            m.put("fiscalLabel", q.getFiscalLabel());
            m.put("availableFrom", q.getAvailableFrom());
            m.put("availableFromEstimated", q.getAvailableFromEstimated());
            m.put("revenue", q.getRevenue());
            m.put("profit", q.getProfit());
            m.put("netMargin", q.getNetMargin());
            m.put("consolidated", q.getConsolidated());
            m.put("revised", q.getRevised());
            out.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", days);
        body.put("results", out);
        body.put("count", out.size());
        body.put("note", "Ordered by the day the company published, not by the day this app read "
                + "it — otherwise a backfill of old quarters presents itself as a week of fresh "
                + "results.");
        return body;
    }

    /** How much of the universe has a captured quarter at all. DB-only. */
    @GetMapping("/coverage")
    public Map<String, Object> coverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("companiesWithResults", service.coveredCompanies());
        out.put("latestPublished", service.latestPublished().orElse(null));
        out.put("captureDuringScreening", config.isCaptureDuringScreening());
        out.put("note", "Captured during the 14:00 screening from filings it already fetches, so "
                + "coverage follows the screening universe and costs no extra NSE requests. A "
                + "company absent here has not been screened recently — it has not necessarily "
                + "failed to report.");
        return out;
    }

    // ------------------------------------------------------------------ write (live NSE)

    /**
     * Fetch and store one company's filed quarters now.
     *
     * <p>POST precisely so no page load can reach it, and refused during the two windows in which
     * the NSE session is spoken for.
     */
    @PostMapping("/capture")
    public Map<String, Object> capture(@RequestParam String symbol) {
        requireOutsideCrunch("Quarterly result capture");
        int written = service.captureLive(symbol);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", SymbolVariants.base(symbol));
        out.put("quartersStored", written);
        return out;
    }

    // ------------------------------------------------------------------ shaping

    private Map<String, Object> view(String symbol, QuarterlyResultService.Reading reading) {
        QuarterlyResultRead.Result r = reading.result();
        EarningsCalendar.Expectation e = reading.expectation();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("resultVerdict", r.verdict().name());
        m.put("resultHeadline", r.headline());
        m.put("resultQuarter", r.fiscalLabel());
        m.put("resultQuarterEnd", r.quarterEnd());
        m.put("resultPublishedOn", r.availableFrom());
        m.put("resultPublishedEstimated", r.availableFromEstimated());
        m.put("resultConsolidated", r.consolidated());
        m.put("resultRevised", r.revised());
        m.put("resultMeasuredSignals", r.measuredSignals());
        m.put("resultTotalSignals", r.totalSignals());
        m.put("resultComparedWith", r.comparedWith());
        m.put("resultBasisNote", r.basisNote());
        m.put("revenueYoyPercent", r.revenueYoyPercent());
        m.put("profitYoyPercent", r.profitYoyPercent());
        m.put("marginDeltaPp", r.marginDeltaPp());
        m.put("revenueQoqPercent", r.revenueQoqPercent());
        m.put("profitQoqPercent", r.profitQoqPercent());
        m.put("resultRevenue", r.revenue());
        m.put("resultProfit", r.profit());
        m.put("resultNetMargin", r.netMargin());
        m.put("resultEps", r.eps());
        m.put("resultTrendBreak", r.trendBreak());
        m.put("resultSymbolAnswered", reading.symbolAnswered());

        List<Map<String, Object>> signals = new ArrayList<>();
        for (QuarterlyResultRead.Signal s : r.signals()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("key", s.key());
            sm.put("label", s.label());
            sm.put("status", s.status().name());
            sm.put("figure", s.figure());
            sm.put("text", s.text());
            signals.add(sm);
        }
        m.put("resultSignals", signals);

        m.put("nextResultStatus", e.status().name());
        m.put("nextResultQuarter", e.nextFiscalLabel());
        m.put("nextResultFrom", e.windowStart());
        m.put("nextResultTo", e.windowEnd());
        m.put("nextResultTypicalLagDays", e.typicalLagDays());
        m.put("nextResultEstimated", e.estimated());
        m.put("nextResultText", e.text());

        List<Map<String, Object>> history = new ArrayList<>();
        for (QuarterlyResultEntity q : reading.quarters()) {
            Map<String, Object> qm = new LinkedHashMap<>();
            qm.put("fiscalLabel", q.getFiscalLabel());
            qm.put("quarterEnd", q.getQuarterEnd());
            qm.put("revenue", q.getRevenue());
            qm.put("profit", q.getProfit());
            qm.put("netMargin", q.getNetMargin());
            qm.put("operatingMargin", q.getOperatingMargin());
            qm.put("eps", q.getEps());
            qm.put("consolidated", q.getConsolidated());
            qm.put("availableFrom", q.getAvailableFrom());
            qm.put("availableFromEstimated", q.getAvailableFromEstimated());
            qm.put("revised", q.getRevised());
            history.add(qm);
        }
        m.put("resultHistory", history);
        return m;
    }

    // ------------------------------------------------------------------ guards

    private void requireOutsideCrunch(String what) {
        ZonedDateTime now = ZonedDateTime.now(marketHoursService.getMarketZone());
        LocalTime t = now.toLocalTime();
        boolean nseCrunch = !t.isBefore(NSE_CRUNCH_START) && !t.isAfter(NSE_CRUNCH_END);
        if (nseCrunch && marketHoursService.isMarketOpen()) {
            throw new CrunchException(String.format("%s makes live NSE calls and is blocked between "
                    + "%s and %s IST: the FII/DII fetch at 09:45 and its report at 10:00 share the "
                    + "same NSE session. Try again after %s.",
                    what, NSE_CRUNCH_START, NSE_CRUNCH_END, NSE_CRUNCH_END));
        }
        if (marketHoursService.isMarketOpen() && !t.isBefore(AFTERNOON_CRUNCH_START)) {
            throw new CrunchException(String.format("%s makes live NSE calls and is blocked from %s "
                    + "to the close: the 14:00 screening, the 14:45 insider capture and the "
                    + "15:05-15:28 report jobs need that session, and those jobs abort silently at "
                    + "15:30. Try again after the close, or before %s IST. The screening captures "
                    + "these results anyway, so there is rarely anything to do here.",
                    what, AFTERNOON_CRUNCH_START, AFTERNOON_CRUNCH_START));
        }
    }

    /** Refusal carrying its own reason — a bare 409 hides the explanation the guard exists to give. */
    static class CrunchException extends RuntimeException {
        CrunchException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(CrunchException.class)
    public ResponseEntity<Map<String, Object>> handleCrunch(CrunchException e) {
        log.info("Earnings request refused: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 409);
        body.put("error", "Blocked");
        body.put("reason", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
