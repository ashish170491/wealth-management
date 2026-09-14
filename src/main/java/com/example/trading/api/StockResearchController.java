package com.example.trading.api;

import com.example.trading.ai.AiService;
import com.example.trading.ai.AnalystSignalService;
import com.example.trading.ai.IntrinsicValuationService;
import com.example.trading.ai.NseDataService;
import com.example.trading.ai.StockDiscoveryService;
import com.example.trading.ai.StockResearchService;
import com.example.trading.holdings.StockValuationService;
import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.scanner.QuantitativeDiscoveryReportService;
import com.example.trading.scanner.QuantitativeDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for AI-powered deep stock research and discovery.
 * All endpoints send results via email AND return JSON.
 */
@RestController
@RequestMapping("/api/research")
@Slf4j
@RequiredArgsConstructor
public class StockResearchController {

    private final StockResearchService researchService;
    private final StockDiscoveryService discoveryService;
    private final NseDataService nseDataService;
    private final EmailNotificationService emailNotificationService;
    private final EmailTemplateService templateService;
    private final QuantitativeDiscoveryReportService quantitativeDiscoveryReportService;
    private final QuantitativeDiscoveryService quantitativeDiscoveryService;
    private final IntrinsicValuationService intrinsicValuationService;
    private final AnalystSignalService analystSignalService;
    private final StockValuationService valuationService;
    private final com.example.trading.fundamentals.CapexCycleService capexCycleService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /**
     * Run deep AI research on a single stock and send via email.
     * Example: GET /api/research/NSE:RELIANCE
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<Map<String, Object>> researchStock(@PathVariable String symbol) {
        // This mapping is a CATCH-ALL under /api/research, and it is expensive: a full
        // 15-dimension research pass, an AI call, and an EMAIL. So a retired sibling path does
        // not 404 -- it silently lands here. Removing GET /api/research/market-direction on
        // 2026-09-03 did exactly that: the old URL returned 200 and ran a research pass on a
        // "stock" called market-direction (SPEC 39.5). Reject anything that is not shaped like an
        // NSE symbol BEFORE doing any work, so a stale link or a typo costs a 404, not an email.
        if (!looksLikeSymbol(symbol)) {
            log.warn("Rejected non-symbol path /api/research/{} -- this endpoint is a catch-all "
                    + "that would otherwise run a full research pass and send an email", symbol);
            return ResponseEntity.status(404).body(Map.of(
                    "status", "NOT_FOUND",
                    "message", "'" + symbol + "' is not a stock symbol. Expected something like "
                            + "NSE:RELIANCE. If you followed a link to a research sub-path, that "
                            + "endpoint no longer exists."));
        }
        log.info("API: Deep stock research requested for {}", symbol);

        try {
            long start = System.currentTimeMillis();
            String analysis = researchService.researchStock(symbol);
            long elapsed = System.currentTimeMillis() - start;

            if (analysis == null || analysis.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "symbol", symbol,
                        "status", "UNAVAILABLE",
                        "message", "AI service not available or analysis failed"
                ));
            }

            // Send email
            sendResearchEmail(symbol, analysis);

            return ResponseEntity.ok(Map.of(
                    "symbol", symbol,
                    "status", "SUCCESS",
                    "message", "Research report sent via email",
                    "elapsedMs", elapsed
            ));
        } catch (Exception e) {
            log.error("Stock research failed for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "symbol", symbol,
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * AI-powered stock discovery and send via email.
     * Example: GET /api/research/discover
     */
    @GetMapping("/discover")
    public ResponseEntity<Map<String, Object>> discoverOpportunities() {
        log.info("API: Stock discovery requested");

        try {
            long start = System.currentTimeMillis();
            String analysis = discoveryService.discoverOpportunities();
            long elapsed = System.currentTimeMillis() - start;

            if (analysis == null || analysis.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "status", "UNAVAILABLE",
                        "message", "AI service not available or discovery failed"
                ));
            }

            // Send email
            sendDiscoveryEmail(analysis);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Discovery report sent via email",
                    "elapsedMs", elapsed
            ));
        } catch (Exception e) {
            log.error("Stock discovery failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Run quantitative data-driven stock discovery scan.
     * No AI — pure quantitative scoring with entry/exit levels.
     * Example: GET /api/research/discover/quantitative
     */
    @GetMapping("/discover/quantitative")
    public ResponseEntity<Map<String, Object>> runQuantitativeDiscovery() {
        log.info("API: Quantitative discovery scan requested");
        try {
            long start = System.currentTimeMillis();
            QuantitativeDiscoveryService.DiscoveryReport report = quantitativeDiscoveryReportService.runAndSendReport();
            long elapsed = System.currentTimeMillis() - start;

            return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "totalScanned", report.getTotalScanned(),
                "totalPassed", report.getTotalPassed(),
                "topOpportunities", report.getOpportunities().stream()
                    .limit(10)
                    .map(o -> Map.of(
                        "symbol", o.getSymbol(),
                        "score", o.getDiscoveryScore(),
                        "price", o.getCurrentPrice(),
                        "entrySignal", o.getLevels() != null ? o.getLevels().getEntrySignal() : "N/A",
                        "exitSignal", o.getLevels() != null ? o.getLevels().getExitSignal() : "N/A"
                    ))
                    .collect(Collectors.toList()),
                "elapsedMs", elapsed,
                "message", "Discovery report sent via email"
            ));
        } catch (Exception e) {
            log.error("Quantitative discovery failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR", "message", e.getMessage()
            ));
        }
    }

    /**
     * Get entry/exit levels and discovery analysis for a single stock.
     * Example: GET /api/research/levels/NSE:RELIANCE
     */
    @GetMapping("/levels/{symbol}")
    public ResponseEntity<Map<String, Object>> getEntryExitLevels(@PathVariable String symbol) {
        log.info("API: Entry/exit levels requested for {}", symbol);
        try {
            QuantitativeDiscoveryService.DiscoveredOpportunity opp = quantitativeDiscoveryService.analyzeSingleStock(symbol);
            if (opp == null) {
                return ResponseEntity.ok(Map.of("symbol", symbol, "status", "INSUFFICIENT_DATA",
                        "message", "Not enough price data for analysis"));
            }
            QuantitativeDiscoveryService.EntryExitLevels levels = opp.getLevels();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("symbol", symbol);
            result.put("status", "SUCCESS");
            result.put("currentPrice", opp.getCurrentPrice());
            result.put("discoveryScore", opp.getDiscoveryScore());
            result.put("entrySignal", levels != null ? levels.getEntrySignal() : "N/A");
            result.put("exitSignal", levels != null ? levels.getExitSignal() : "N/A");
            if (levels != null) {
                result.put("entryZone", Map.of(
                        "low", levels.getIdealEntryZoneLow() != null ? levels.getIdealEntryZoneLow() : 0,
                        "high", levels.getIdealEntryZoneHigh() != null ? levels.getIdealEntryZoneHigh() : 0));
                result.put("stopLoss", levels.getStopLoss() != null ? levels.getStopLoss() : 0);
                result.put("targets", Map.of(
                        "target1_1_5R", levels.getTarget1() != null ? levels.getTarget1() : 0,
                        "target2_2_5R", levels.getTarget2() != null ? levels.getTarget2() : 0,
                        "target3_4R", levels.getTarget3() != null ? levels.getTarget3() : 0));
                result.put("riskRewardRatio", levels.getRiskRewardRatio() != null ? levels.getRiskRewardRatio() : 0);
                result.put("rsi14", levels.getRsi14() != null ? levels.getRsi14() : 0);
                result.put("support", Map.of(
                        "s1", levels.getSupport1() != null ? levels.getSupport1() : 0,
                        "s2", levels.getSupport2() != null ? levels.getSupport2() : 0));
                result.put("resistance", Map.of(
                        "r1", levels.getResistance1() != null ? levels.getResistance1() : 0,
                        "r2", levels.getResistance2() != null ? levels.getResistance2() : 0));
                result.put("ema", Map.of(
                        "ema20", levels.getEma20() != null ? levels.getEma20() : 0,
                        "ema50", levels.getEma50() != null ? levels.getEma50() : 0,
                        "ema200", levels.getEma200() != null ? levels.getEma200() : 0));
            }
            result.put("triggers", opp.getTriggers());
            result.put("risks", opp.getRisks());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Entry/exit analysis failed for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR", "message", e.getMessage()));
        }
    }

    // ============================================================
    // Email Builders
    // ============================================================

    private void sendResearchEmail(String symbol, String analysis) {
        try {
            String htmlContent = AiService.formatAiResponseAsHtml("Deep Research: " + symbol, analysis);

            String html = templateService.buildEmailTemplate(
                    "AI Deep Stock Research",
                    "15-Dimension Analysis for " + symbol,
                    htmlContent
            );

            String subject = String.format("AI Research Report — %s | %s",
                    symbol, LocalDate.now().format(DATE_FMT));

            emailNotificationService.sendHtmlEmail(subject, html);
            log.info("Deep research email sent for {}", symbol);
        } catch (Exception e) {
            log.error("Failed to send research email for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * AI-powered universe expansion analysis.
     * Suggests new stocks and identifies sector gaps.
     * Example: GET /api/research/universe/expand
     */
    @GetMapping("/universe/expand")
    public ResponseEntity<Map<String, Object>> suggestUniverseExpansion() {
        log.info("API: Universe expansion analysis requested");
        try {
            long start = System.currentTimeMillis();
            StockDiscoveryService.UniverseExpansionResult result = discoveryService.suggestUniverseExpansion();
            long elapsed = System.currentTimeMillis() - start;

            // Send email with report
            String report = discoveryService.getUniverseExpansionReport();
            if (report != null && !report.isEmpty()) {
                sendUniverseExpansionEmail(report);
            }

            return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "currentUniverseSize", result.getCurrentUniverseSize(),
                "newFromHoldings", result.getNewStocksFromHoldings().size(),
                "aiSuggested", result.getAiSuggestedStocks().size(),
                "underrepresentedSectors", result.getUnderrepresentedSectors(),
                "elapsedMs", elapsed
            ));
        } catch (Exception e) {
            log.error("Universe expansion analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR", "message", e.getMessage()
            ));
        }
    }

    /**
     * Analyst signal for a stock (SPEC §24) — two honest proxies for paid consensus:
     *   - Earnings trend-break: latest quarter vs 3-quarter linear projection
     *   - Brokerage actions: keyword-matched upgrades/downgrades from last 7d news
     * Example: GET /api/research/analyst/NSE:RELIANCE
     */
    @GetMapping("/analyst/{symbol}")
    public ResponseEntity<Map<String, Object>> getAnalystSignal(@PathVariable String symbol) {
        log.info("API: Analyst signal requested for {}", symbol);
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            AnalystSignalService.AnalystSignal sig = analystSignalService.analyze(tradingSymbol, null);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("symbol", symbol);
            body.put("status", "SUCCESS");
            body.put("verdict", sig.getVerdict());
            body.put("aggregateScore", sig.getAggregateScore());
            if (sig.getTrendBreak() != null) body.put("trendBreak", sig.getTrendBreak());
            if (sig.getBrokerageActions() != null) body.put("brokerageActions", sig.getBrokerageActions());
            body.put("methodologyNote",
                    "Not paid analyst consensus. Trend-break = latest-quarter actuals vs a 3-quarter linear projection " +
                    "(proxy for EPS/revenue surprise). Brokerage actions = keyword-matched upgrades/downgrades from " +
                    "Google News RSS over the last 7 days, strict-filtered to a named brokerage or an explicit target. " +
                    "Expect ~30% miss rate and occasional false positives; treat as supporting evidence only.");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR", "message", e.getMessage()
            ));
        }
    }

    /**
     * Reverse-DCF sanity check for a stock (SPEC §12.5).
     * Returns implied growth, historical growth, verdict, and sensitivity band.
     * Example: GET /api/research/valuation/NSE:RELIANCE
     */
    @GetMapping("/valuation/{symbol}")
    public ResponseEntity<Map<String, Object>> getIntrinsicValuation(@PathVariable String symbol) {
        log.info("API: Reverse DCF requested for {}", symbol);
        try {
            IntrinsicValuationService.ReverseDcfResult dcf = intrinsicValuationService.analyze(symbol);
            if (dcf == null) {
                return ResponseEntity.ok(Map.of("symbol", symbol, "status", "NO_DATA"));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("symbol", symbol);
            body.put("status", "SUCCESS");
            body.put("verdict", dcf.getVerdict());
            body.put("impliedGrowthPercent", dcf.getImpliedGrowthPercent());
            body.put("historicalGrowthPercent", dcf.getHistoricalGrowthPercent());
            body.put("expectationGapPercent", dcf.getExpectationGapPercent());
            body.put("impliedGrowthAtLowDiscountPercent", dcf.getImpliedGrowthAtLowDiscountPercent());
            body.put("impliedGrowthAtHighDiscountPercent", dcf.getImpliedGrowthAtHighDiscountPercent());
            body.put("marketCapCr", dcf.getMarketCapCr());
            body.put("latestAnnualisedProfitCr", dcf.getLatestAnnualisedProfitCr());
            body.put("latestAnnualisedFcfProxyCr", dcf.getLatestAnnualisedFcfProxyCr());
            body.put("discountRate", dcf.getDiscountRate());
            body.put("terminalGrowth", dcf.getTerminalGrowth());
            body.put("forecastYears", dcf.getForecastYears());
            if (dcf.getCaveat() != null) body.put("caveat", dcf.getCaveat());
            body.put("methodologyNote",
                    "Reverse DCF. Solves the growth rate that justifies today's price. " +
                    "Sanity check, not a price target. Known bias: under-values long-duration compounders " +
                    "(IT/platforms/pharma) — treat their EXPENSIVE verdict skeptically.");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR", "message", e.getMessage()
            ));
        }
    }

    /**
     * Get earnings growth analysis for a stock.
     * Example: GET /api/research/earnings/NSE:RELIANCE
     */
    @GetMapping("/earnings/{symbol}")
    public ResponseEntity<Map<String, Object>> getEarningsGrowth(@PathVariable String symbol) {
        log.info("API: Earnings growth analysis requested for {}", symbol);
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            NseDataService.EarningsGrowthData data = nseDataService.analyzeEarningsGrowth(tradingSymbol);
            if (data == null) {
                return ResponseEntity.ok(Map.of("symbol", symbol, "status", "NO_DATA"));
            }
            return ResponseEntity.ok(Map.of(
                "symbol", symbol,
                "status", "SUCCESS",
                "growthVerdict", data.getGrowthVerdict() != null ? data.getGrowthVerdict() : "N/A",
                "yoyRevenueGrowth", data.getYoyRevenueGrowth() != null ? data.getYoyRevenueGrowth() : "N/A",
                "yoyProfitGrowth", data.getYoyProfitGrowth() != null ? data.getYoyProfitGrowth() : "N/A",
                "earningsAccelerating", data.isEarningsAccelerating(),
                "consecutiveGrowthQuarters", data.getConsecutiveGrowthQuarters(),
                "latestNetMargin", data.getLatestNetMargin() != null ? data.getLatestNetMargin() : "N/A"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR", "message", e.getMessage()
            ));
        }
    }

    /**
     * Capital-efficiency metrics from the latest annual balance sheet (SPEC §12.8):
     * ROCE, ROE, Debt-to-Equity, real cash conversion, dividend payout.
     * Example: GET /api/research/capital-efficiency/NSE:RELIANCE
     */
    @GetMapping("/capital-efficiency/{symbol}")
    public ResponseEntity<Map<String, Object>> getCapitalEfficiency(@PathVariable String symbol) {
        log.info("API: Capital-efficiency analysis requested for {}", symbol);
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            // Resolve the industry so NBFCs/insurers are flagged financial (their ROCE/D-E aren't
            // comparable). Best-effort — null hint just falls back to banking-taxonomy detection.
            String industryHint = null;
            try {
                StockValuationService.ValuationData v = valuationService.getValuationData(symbol);
                if (v != null) industryHint = v.getIndustry();
            } catch (Exception ignored) {
                // industry resolution is optional; proceed without it
            }
            NseDataService.CapitalEfficiencyData ce = nseDataService.analyzeCapitalEfficiency(tradingSymbol, industryHint);
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("symbol", symbol);
            if (ce == null || !ce.isApplicable()) {
                body.put("status", "NO_DATA");
                body.put("reason", ce != null ? ce.getNaReason() : "Annual financials unavailable");
                return ResponseEntity.ok(body);
            }
            body.put("status", "SUCCESS");
            body.put("financialYear", ce.getFinancialYear());
            body.put("consolidated", ce.isConsolidated());
            body.put("financialSector", ce.isFinancialSector());
            body.put("overallVerdict", ce.getOverallVerdict());
            body.put("roePercent", ce.getRoePercent());
            body.put("roeVerdict", ce.getRoeVerdict());
            body.put("roaPercent", ce.getRoaPercent());
            body.put("roaVerdict", ce.getRoaVerdict());
            body.put("rocePercent", ce.getRocePercent());
            body.put("roceVerdict", ce.getRoceVerdict());
            body.put("debtToEquity", ce.getDebtToEquity());
            body.put("leverageVerdict", ce.getLeverageVerdict());
            body.put("cashConversionRatio", ce.getCashConversionRatio());
            body.put("cashConversionVerdict", ce.getCashConversionVerdict());
            body.put("dividendPayoutPercent", ce.getDividendPayoutPercent());
            body.put("strengths", ce.getStrengths());
            body.put("redFlags", ce.getRedFlags());
            body.put("methodology", "ROCE=(PBT+FinanceCosts)/(Equity+TotalBorrowings); ROE=NetProfit/Equity; "
                    + "D/E=TotalBorrowings/Equity; CashConversion=OperatingCashFlow/NetProfit. Source: latest annual Ind-AS XBRL on NSE.");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * Capex-cycle signal (SPEC.md §31) — capacity being built now that the P&amp;L will not
     * show for another 12-24 months. DB/XBRL-backed, sends no email.
     * Example: GET /api/research/capex/NSE:RELIANCE
     */
    @GetMapping("/capex/{symbol}")
    public ResponseEntity<Map<String, Object>> getCapexCycle(@PathVariable String symbol) {
        log.info("API: Capex-cycle analysis requested for {}", symbol);
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            String industryHint = null;
            try {
                StockValuationService.ValuationData v = valuationService.getValuationData(symbol);
                if (v != null) industryHint = v.getIndustry();
            } catch (Exception ignored) {
                // industry resolution is optional; banking-taxonomy detection still applies
            }
            NseDataService.CapexCycleData cx = capexCycleService.analyze(symbol, industryHint);
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("symbol", symbol);
            body.put("verdict", cx.getVerdict());
            body.put("reason", cx.getReason());
            body.put("applicable", cx.isApplicable());
            if (!cx.isApplicable()) {
                body.put("status", "NO_DATA");
                return ResponseEntity.ok(body);
            }
            body.put("status", "SUCCESS");
            body.put("financialYear", cx.getFinancialYear());
            body.put("cwipCurrent", cx.getCwipCurrent());
            body.put("cwipPrior", cx.getCwipPrior());
            body.put("cwipChange", cx.getCwipChange());
            body.put("cwipGrowthPercent", cx.getCwipGrowthPercent());
            body.put("cwipIntensityPercent", cx.getCwipIntensityPercent());
            body.put("netBlock", cx.getNetBlock());
            body.put("depreciation", cx.getDepreciation());
            body.put("capexProxy", cx.getCapexProxy());
            body.put("capexToDepreciation", cx.getCapexToDepreciation());
            body.put("priorYearAvailable", cx.isPriorYearAvailable());
            body.put("priorYearFactCount", cx.getPriorYearFactCount());
            body.put("methodology", "CWIP intensity = CapitalWorkInProgress / PropertyPlantAndEquipment; "
                    + "capex proxy = dPPE + dCWIP + depreciation; capex/dep = proxy / depreciation. "
                    + "Prior-year figures come from the SAME filing's comparative column, so a year-on-year "
                    + "delta is available despite integrated filings only starting Mar-2025. "
                    + "Not computed for banks/financials — their growth is the loan book, not plant.");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * Get shareholding history and trend for a stock.
     * Example: GET /api/research/shareholding/NSE:RELIANCE
     */
    @GetMapping("/shareholding/{symbol}")
    public ResponseEntity<Map<String, Object>> getShareholdingHistory(@PathVariable String symbol) {
        log.info("API: Shareholding history requested for {}", symbol);
        try {
            String tradingSymbol = symbol.contains(":") ? symbol.split(":")[1] : symbol;
            NseDataService.ShareholdingHistory history = nseDataService.fetchShareholdingHistory(tradingSymbol);
            if (history == null) {
                return ResponseEntity.ok(Map.of("symbol", symbol, "status", "NO_DATA"));
            }
            return ResponseEntity.ok(Map.of(
                "symbol", symbol,
                "status", "SUCCESS",
                "insiderSignal", history.getInsiderSignal() != null ? history.getInsiderSignal() : "N/A",
                "promoterChange", history.getPromoterChange() != null ? history.getPromoterChange() : "N/A",
                "promoterIncreasing", history.isPromoterIncreasing(),
                "fiiIncreasing", history.isFiiIncreasing(),
                "pledgePercent", history.getPledgePercent() != null ? history.getPledgePercent() : "N/A",
                "quartersTracked", history.getQuarters() != null ? history.getQuarters().size() : 0
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR", "message", e.getMessage()
            ));
        }
    }

    // ============================================================
    // Email Builders
    // ============================================================

    private void sendUniverseExpansionEmail(String report) {
        try {
            String htmlContent = AiService.formatAiResponseAsHtml(
                "Stock Universe Expansion Analysis", report);
            String html = templateService.buildEmailTemplate(
                "Universe Expansion Report",
                "New Stocks to Screen & Sector Gap Analysis",
                htmlContent
            );
            String subject = String.format("Universe Expansion — %s | New Stocks & Sector Gaps",
                LocalDate.now().format(DATE_FMT));
            emailNotificationService.sendHtmlEmail(subject, html);
            log.info("Universe expansion email sent");
        } catch (Exception e) {
            log.error("Failed to send universe expansion email: {}", e.getMessage());
        }
    }

    private void sendDiscoveryEmail(String analysis) {
        try {
            String htmlContent = AiService.formatAiResponseAsHtml(
                    "AI Stock Discovery — Emerging Themes, Hidden Gems & Contrarian Ideas", analysis);

            String html = templateService.buildEmailTemplate(
                    "AI Stock Discovery Report",
                    "New Investment Opportunities & Portfolio Analysis",
                    htmlContent
            );

            String subject = String.format("AI Stock Discovery — %s | Themes, Gems & Contrarian Ideas",
                    LocalDate.now().format(DATE_FMT));

            emailNotificationService.sendHtmlEmail(subject, html);
            log.info("Stock discovery email sent");
        } catch (Exception e) {
            log.error("Failed to send discovery email: {}", e.getMessage());
        }
    }

    /**
     * A path segment is a symbol only if it looks like one: {@code NSE:RELIANCE}, {@code RELIANCE},
     * {@code M&M}, {@code BAJAJ-AUTO}. Lower-case is rejected because every real tradingsymbol is
     * upper-case, and it is lower-case kebab paths ({@code market-direction}, {@code universe})
     * that collide with this catch-all.
     */
    static boolean looksLikeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank() || symbol.length() > 40) {
            return false;
        }
        String bare = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
        if (bare.isBlank()) {
            return false;
        }
        boolean hasLetter = false;
        for (char c : bare.toCharArray()) {
            if (Character.isLowerCase(c)) {
                return false;
            }
            if (Character.isUpperCase(c)) {
                hasLetter = true;
            } else if (!Character.isDigit(c) && c != '-' && c != '&' && c != '.' && c != ' ') {
                return false;
            }
        }
        return hasLetter;
    }
}
