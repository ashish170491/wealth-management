package com.example.trading.funds;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the mutual fund record (SPEC §52).
 *
 * <p>There is deliberately <b>no</b> {@code actionable}, {@code bonus}, {@code weight} or
 * {@code points} field here, and {@code FundSurfaceContractTest} fails if one appears anywhere in
 * this package. That is what makes "contributes zero points to any score" checkable rather than a
 * claim in a comment (SPEC §52.2d, the §51 precedent).
 *
 * <p>Shadow mode exists for a signal that would otherwise steer the portfolio before it could be
 * judged (Gotcha 30). This module steers nothing, so it has nothing to shadow — what it must not
 * acquire is the switch that would let it start.
 */
@Component
@ConfigurationProperties(prefix = "trading.funds")
@Data
public class MutualFundConfig {

    /**
     * Whether the 12:30 AMFI pull runs at all. The job is otherwise unconditional — one HTTP GET to
     * a third host, competing for neither the Kite nor the NSE budget.
     */
    private boolean navPullEnabled = true;

    /** AMFI's daily all-scheme NAV file. Free, no key, ~1.5 MB. */
    private String navAllUrl = "https://portal.amfiindia.com/spages/NAVAll.txt";

    /**
     * Response buffer for the AMFI client.
     *
     * <p>Spring's WebClient default is <b>256 KB</b> and the file is 1.5 MB, so on the default this
     * feature is born broken: the request fails, the catch degrades to an empty list, and every
     * consumer downstream reads that as <em>"there are no mutual funds"</em>. That is B-054
     * verbatim, and CLAUDE.md had documented the identical failure for the Kite instruments CSV
     * long before it happened to the NSE client (Gotcha 52).
     */
    private int maxResponseBytes = 16 * 1024 * 1024;

    private int timeoutSeconds = 90;

    /**
     * Store a daily NAV row for every screenable scheme, not only for the ones the investor owns.
     *
     * <p>On by default: ~571 rows a day is nothing next to {@code multibagger_scores}, and it is
     * what eventually makes the survivorship ledger measurable rather than a caveat (SPEC §52.7).
     */
    private boolean storeNavForScreenableUniverse = true;

    /**
     * Minimum stored monthly NAV points before any rolling measurement may be published.
     *
     * <p>36 months, and it is a refusal rather than a tuning knob: below it the whole lens reads
     * {@code NOT_MEASURED}. A window that cannot reach its period returns a constant rather than a
     * measurement, which is how monthly RSI read exactly 50.0 across the entire universe for months
     * (B-060). Stage 2 reads this; Stage 1 stores against it.
     */
    private int minMonthsToMeasure = 36;
}
