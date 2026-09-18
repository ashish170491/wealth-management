package com.example.trading.earnings;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for quarterly result tracking (SPEC.md §50.8).
 *
 * <p>There is deliberately <b>no</b> {@code actionable} flag here, because there is nothing to
 * switch on: this feature contributes zero points to any score, feeds no verdict and adjusts no
 * weight. Shadow mode exists for a signal that would otherwise steer the portfolio before it
 * could be judged (Gotcha 30); a ledger that steers nothing has nothing to shadow. What it does
 * instead is register a coverage row, so that when a result-based signal is eventually proposed
 * the evidence for it already exists.
 */
@Component
@ConfigurationProperties(prefix = "trading.earnings")
@Data
public class EarningsConfig {

    /**
     * Persist quarterly figures during the daily screening run.
     *
     * <p>On by default and effectively free: the screening already fetches these filings for the
     * earnings-growth bonus and the 30-minute cache means the capture costs <b>zero extra NSE
     * requests</b>. It is a switch rather than a hard-coded call only so that a screening run can
     * be isolated from this table while diagnosing one.
     */
    private boolean captureDuringScreening = true;

    /**
     * How many days a filing stays "new" for the purposes of the result alert.
     *
     * <p>Fourteen because results arrive in a cluster over roughly a fortnight each season, and a
     * window shorter than the reporting season would miss a company whose capture happened to
     * fall on a weekend.
     */
    private int newResultWindowDays = 14;

    /** How many quarters to keep per company. Eight is two full years — enough for YoY plus trend. */
    private int maxQuartersPerSymbol = 12;

    /** Cap on the rows a single recent-results request returns. */
    private int recentLimit = 200;
}
