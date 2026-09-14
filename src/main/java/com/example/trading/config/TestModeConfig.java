package com.example.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for test mode - trade only specific stocks for ML strategy testing.
 */
@Configuration
@ConfigurationProperties(prefix = "trading.test-mode")
@Data
public class TestModeConfig {

    /**
     * Enable/disable test mode.
     * When enabled, only stocks in test-stocks list will be traded.
     */
    private boolean enabled = false;

    /**
     * List of stock symbols to trade in test mode.
     * Use full symbols with exchange prefix (e.g., "NSE:RELIANCE").
     */
    private List<String> testStocks = new ArrayList<>();

    /**
     * Check if a symbol is in the test stocks list.
     * @param symbol Stock symbol (with or without exchange prefix)
     * @return true if in test list or test mode is disabled
     */
    public boolean isAllowedForTrading(String symbol) {
        if (!enabled || testStocks == null || testStocks.isEmpty()) {
            return true; // Allow all if test mode disabled
        }
        String normalizedSymbol = symbol.toUpperCase();
        return testStocks.stream()
            .map(String::toUpperCase)
            .anyMatch(s -> s.equals(normalizedSymbol) ||
                          normalizedSymbol.endsWith(":" + s.replace("NSE:", "").replace("BSE:", "")));
    }

    /**
     * Get test stocks summary for logging.
     */
    public String getTestStocksSummary() {
        if (!enabled) {
            return "Test mode disabled - trading all stocks";
        }
        if (testStocks == null || testStocks.isEmpty()) {
            return "No test stocks configured";
        }
        return String.format("Test mode enabled with %d stocks: %s",
            testStocks.size(), String.join(", ", testStocks));
    }
}
