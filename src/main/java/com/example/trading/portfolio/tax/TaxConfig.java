package com.example.trading.portfolio.tax;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Indian equity capital-gains tax configuration. See SPEC.md §9.
 *
 * <p>Defaults reflect FY26 rules (post-Budget 2024):
 * <ul>
 *   <li>STCG: 20% on equity held ≤ 365 days (raised from 15% in Budget 2024)</li>
 *   <li>LTCG: 12.5% on equity held > 365 days, with ₹1.25 L/year exemption</li>
 * </ul>
 * Values are overridable in {@code application.yml} under {@code portfolio.tax.*}.
 */
@Component
@ConfigurationProperties(prefix = "portfolio.tax")
@Data
public class TaxConfig {
    /** Short-term capital gains rate for equity (percent). */
    private double stcgRatePercent = 20.0;

    /** Long-term capital gains rate for equity (percent). */
    private double ltcgRatePercent = 12.5;

    /** Days held threshold above which gains become long-term. */
    private int ltcgCutoffDays = 365;

    /** Annual LTCG exemption in rupees — first ₹1.25 L of LTCG is tax-free. */
    private double ltcgExemptionPerYear = 125000.0;

    /** Default lot-matching method when a sale is recorded: FIFO | LIFO | HIFO. */
    private String defaultMatchingMethod = "FIFO";

    /** Flag lots within this many days of the LTCG cutoff as "approaching cutoff" in harvest output. */
    private int approachingCutoffWindowDays = 30;
}
