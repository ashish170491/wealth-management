package com.example.trading.intelligence;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for the headline scan that feeds the macro event ledger (SPEC §48.2).
 * Bound from {@code trading.market-impact-news.*}.
 *
 * <p><b>B-102.</b> That yaml block used to carry six keys - {@code check-interval-minutes},
 * {@code max-news-age-hours}, {@code send-critical-alerts}, {@code send-high-alerts} and
 * {@code daily-summary-enabled} - and <b>nothing read any of them</b>. There was no properties
 * class at all: the interval was hard-coded in a cron expression, the age window hard-coded at two
 * hours, and the two "send alerts" switches could not turn off emails that went out every fifteen
 * minutes. Configuration that looks like a control and is not one is worse than no configuration,
 * because the next person to want the behaviour changed will change the key and believe it worked.
 *
 * <p>Both fields below are read. Do not add a key to the yaml without a field here to receive it.
 */
@Configuration
@ConfigurationProperties(prefix = "trading.market-impact-news")
@Data
public class MarketImpactNewsConfig {

    /** False stops the fifteen-minute scan entirely. */
    private boolean enabled = true;

    /**
     * Cap on the catch-up window when the headline table is stale.
     *
     * <p>The scan normally fetches everything published since the newest headline it already has,
     * so a gap fills itself; this bounds a first run, or a run after a long weekend, so it cannot
     * try to pull a month of feeds at once.
     *
     * <p>It replaced a hard-coded two hours, which had a quiet and serious consequence: the first
     * scan of the day fires at 09:15 and could therefore only ever see back to 07:15, so <b>every
     * overnight story was invisible to this app</b> - which is most of what happens in the world
     * that matters to an Indian portfolio, since the US closes and Asia opens while India sleeps.
     */
    private int maxLookbackHours = 36;
}
