package com.example.trading.risk;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "trading.risk")
@Data
@Validated
public class RiskConfig {
    @Positive(message = "Total capital must be positive")
    private double totalCapital = 100000.0;

    @Positive(message = "Max risk per trade percentage must be positive")
    private double maxRiskPerTradePct = 0.01; // 1%

    @Positive(message = "Max daily loss percentage must be positive")
    private double maxDailyLossPct = 0.03; // 3%

    @Min(value = 1, message = "Max open positions must be at least 1")
    private int maxOpenPositions = 5;

    @Positive(message = "Daily profit target must be positive")
    private double dailyProfitTarget = 3000.0; // INR

    // Fund validation settings
    private boolean enableFundValidation = false; // Disabled by default for paper trading

    @Positive(message = "Margin multiplier must be positive")
    private double marginMultiplier = 0.2; // 20% margin for MIS (5x leverage)

    @Min(value = 1, message = "Minimum quantity must be at least 1")
    private int minimumQuantity = 1; // Minimum quantity for a valid trade

    // Maximum position value cap (prevents oversized positions)
    @Positive(message = "Max position value must be positive")
    private double maxPositionValue = 15000.0; // Max ₹15,000 per position (base)

    // Elevated max position value for strong trending regimes (ADX > 25)
    @Positive(message = "Max position value for trending must be positive")
    private double maxPositionValueTrending = 25000.0; // Max ₹25,000 in trending regimes

    // Maximum loss per trade in absolute rupees (hard cap regardless of position size)
    @Positive(message = "Max loss per trade must be positive")
    private double maxLossPerTrade = 75.0; // Max ₹75 loss per trade

    // Maximum quantity caps per price tier (prevents high quantity on low-priced stocks)
    private int maxQuantityTier1 = 100;   // For stocks below ₹100
    private int maxQuantityTier2 = 50;    // For stocks ₹100-500
    private int maxQuantityTier3 = 20;    // For stocks ₹500-1000
    private int maxQuantityTier4 = 10;    // For stocks ₹1000-2000
    private int maxQuantityTier5 = 5;     // For stocks ₹2000-5000
    private int maxQuantityTier6 = 2;     // For stocks above ₹5000

    /**
     * Get maximum allowed quantity based on stock price tier.
     */
    public int getMaxQuantityForPrice(double price) {
        if (price < 100) return maxQuantityTier1;
        if (price < 500) return maxQuantityTier2;
        if (price < 1000) return maxQuantityTier3;
        if (price < 2000) return maxQuantityTier4;
        if (price < 5000) return maxQuantityTier5;
        return maxQuantityTier6;
    }
}
