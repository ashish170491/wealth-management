package com.example.trading.ai;

import com.example.trading.ai.NseDataService.EarningsTrendBreakData;
import com.example.trading.ai.StockNewsService.BrokerageActionData;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Combines the two honest proxies we have for "analyst signal" in the absence
 * of a paid consensus feed (see SPEC.md §24):
 * <ul>
 *   <li>Earnings <em>trend break</em> — surprise vs a 3-quarter linear projection,
 *       computed from NSE quarterly filings we already fetch.</li>
 *   <li>Brokerage <em>action signal</em> — keyword-matched upgrade / downgrade
 *       events scraped from Google News RSS over the last 7 days.</li>
 * </ul>
 *
 * Produces a single bounded score (<code>-10..+10</code>) plus an aggregate
 * verdict that downstream callers (Multibagger bonus, Deep Research, holdings
 * attention items) consume. Returns a non-null result with empty components
 * even when both inputs fail — downstream code must never NPE on this service.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalystSignalService {

    private final NseDataService nseDataService;
    private final StockNewsService stockNewsService;

    public AnalystSignal analyze(String tradingSymbol, String companyName) {
        AnalystSignal signal = new AnalystSignal();
        signal.setSymbol(tradingSymbol);

        try {
            signal.setTrendBreak(nseDataService.analyzeEarningsTrendBreak(tradingSymbol));
        } catch (Exception e) {
            log.debug("Analyst signal: trend-break unavailable for {}: {}", tradingSymbol, e.getMessage());
        }

        try {
            signal.setBrokerageActions(stockNewsService.detectBrokerageActions(tradingSymbol, companyName));
        } catch (Exception e) {
            log.debug("Analyst signal: brokerage scan failed for {}: {}", tradingSymbol, e.getMessage());
        }

        // Aggregate score — bounded ±10. Trend break dominates (±7), brokerage action reinforces (±3).
        int score = 0;
        if (signal.getTrendBreak() != null && signal.getTrendBreak().getVerdict() != null) {
            score += switch (signal.getTrendBreak().getVerdict()) {
                case "BIG_POSITIVE_BREAK" -> 7;
                case "POSITIVE_BREAK" -> 4;
                case "IN_LINE" -> 0;
                case "NEGATIVE_BREAK" -> -4;
                case "BIG_NEGATIVE_BREAK" -> -7;
                default -> 0;
            };
        }
        if (signal.getBrokerageActions() != null && signal.getBrokerageActions().getVerdict() != null) {
            score += switch (signal.getBrokerageActions().getVerdict()) {
                case "POSITIVE_FLOW" -> 3;
                case "MIXED_POSITIVE" -> 1;
                case "NEUTRAL", "NO_COVERAGE" -> 0;
                case "MIXED_NEGATIVE" -> -1;
                case "NEGATIVE_FLOW" -> -3;
                default -> 0;
            };
        }
        score = Math.max(-10, Math.min(10, score));
        signal.setAggregateScore(score);
        signal.setVerdict(classify(score));
        return signal;
    }

    private String classify(int score) {
        if (score >= 7) return "STRONG_POSITIVE";
        if (score >= 3) return "POSITIVE";
        if (score <= -7) return "STRONG_NEGATIVE";
        if (score <= -3) return "NEGATIVE";
        return "NEUTRAL";
    }

    @Data
    public static class AnalystSignal {
        private String symbol;
        private EarningsTrendBreakData trendBreak;
        private BrokerageActionData brokerageActions;
        /** Bounded <code>-10..+10</code>. Trend-break contributes ±7, brokerage flow ±3. */
        private int aggregateScore;
        /** STRONG_POSITIVE / POSITIVE / NEUTRAL / NEGATIVE / STRONG_NEGATIVE. */
        private String verdict;
    }
}
