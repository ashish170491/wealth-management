package com.example.trading.universe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for dynamic universe expansion (SPEC §30).
 *
 * <p>Defaults are deliberately conservative: the feature widens what the screener looks at,
 * and a funnel that promotes too freely turns a curated 361-name universe into a list nobody
 * can review.
 */
@Data
@Component
@ConfigurationProperties(prefix = "trading.universe.dynamic-expansion")
public class UniverseConfig {

    /**
     * Master switch. When false, discovered symbols are still scanned, queued and scored —
     * they simply do not enter the screening universe. That makes it safe to watch the funnel
     * behave for a few weeks before letting it change what gets screened.
     */
    private boolean enabled = false;

    /** Whether the Saturday coarse scan runs at all. Independent of {@link #enabled}. */
    private boolean scanEnabled = true;

    /** Hard ceiling on promoted symbols. Beyond this, the weakest is retired first. */
    private int maxActiveSymbols = 100;

    /** Survivors kept from each coarse scan. */
    private int coarseScanTopN = 40;

    /** Queued symbols deep-scored per weekday run — bounds the NSE/XBRL cost per day. */
    private int deepScorePerDay = 10;

    /** Minimum 20-day average traded value, rupees. Mirrors the MODERATE liquidity floor. */
    private double minAdv20d = 50_00_000d;

    /** Minimum months since listing for the coarse scan; newer names go to the IPO tracker. */
    private int minMonthsSinceListing = 6;

    /** Composite required to be promoted into the screening universe. */
    private int promotionMinComposite = 60;

    /** Composite below which a promoted symbol accrues a weak run. */
    private int retirementComposite = 45;

    /** Consecutive weak weekly runs before a promoted symbol is retired. */
    private int retirementConsecutiveRuns = 8;

    /**
     * Months a retired symbol stays out of the coarse scan before it can be re-discovered
     * (B-036). Without a cool-off, retirement is cosmetic: the next Saturday scan finds the
     * symbol again on the same price/volume filters that promoted it and puts it straight
     * back. With permanent exclusion, the scannable pool only ever shrinks.
     */
    private int retirementCooloffMonths = 6;

    /** A listing is "recent" for this many months — the IPO tracker's window. */
    private int recentIpoMonths = 36;
}
