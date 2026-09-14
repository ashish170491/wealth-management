package com.example.trading.portfolio;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Portfolio module configuration. See SPEC.md §5 Portfolio Goals & Allocation.
 */
@Component
@ConfigurationProperties(prefix = "portfolio")
@Data
public class PortfolioConfig {
    private boolean enabled = true;

    /** Drift alert fires if |actual − target| exceeds this many percentage points. */
    private double defaultTolerancePp = 5.0;

    /** Drift alert fires if |actual − target| / target exceeds this ratio (0.25 = 25%). */
    private double defaultRelativeTolerance = 0.25;

    /** Market-cap boundary in ₹ crores: below this = small cap. Matches multibagger module boundaries. */
    private double smallCapMax = 10000.0;

    /** Market-cap boundary in ₹ crores: below this (and above smallCapMax) = mid cap. */
    private double midCapMax = 50000.0;

    /** Seed a default portfolio profile at startup if none exists. */
    private boolean seedOnStartup = true;

    private String defaultProfileName = "Default Portfolio";
}
