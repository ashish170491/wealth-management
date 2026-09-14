package com.example.trading.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for stock filtering - blacklist, price limits, volume filters.
 */
@Configuration
@ConfigurationProperties(prefix = "stock-filter")
@Data
public class StockFilterConfig {

    /**
     * Enable/disable stock filtering.
     */
    private boolean enabled = true;

    /**
     * List of stock symbols to exclude from trading.
     * These are raw symbols without exchange prefix (e.g., "RBLBANK", not "NSE:RBLBANK").
     */
    private List<String> blacklist = new ArrayList<>();

    /**
     * Minimum stock price to trade.
     */
    private double minPrice = 50.0;

    /**
     * Maximum stock price to trade.
     */
    private double maxPrice = 50000.0;

    /**
     * Minimum average daily volume in lakhs.
     */
    private double minAvgVolumeLakhs = 10.0;

    /**
     * Check if a symbol is blacklisted.
     * @param symbol Stock symbol (with or without exchange prefix)
     * @return true if blacklisted
     */
    public boolean isBlacklisted(String symbol) {
        if (!enabled || blacklist == null || blacklist.isEmpty()) {
            return false;
        }
        String rawSymbol = symbol.replace("NSE:", "").replace("BSE:", "").toUpperCase();
        return blacklist.stream()
            .map(String::toUpperCase)
            .anyMatch(b -> b.equals(rawSymbol));
    }

    /**
     * Check if price is within acceptable range.
     * @param price Current stock price
     * @return true if price is acceptable
     */
    public boolean isPriceAcceptable(double price) {
        if (!enabled) {
            return true;
        }
        return price >= minPrice && price <= maxPrice;
    }

    /**
     * Get the blacklist as a formatted string for logging.
     */
    public String getBlacklistSummary() {
        if (blacklist == null || blacklist.isEmpty()) {
            return "No stocks blacklisted";
        }
        return String.join(", ", blacklist);
    }
}
