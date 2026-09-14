package com.example.trading.portfolio.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Core Holding Classifier configuration (SPEC §35).
 *
 * <p>Every key is listed in {@code application.yml} under {@code portfolio.core.*}. These are
 * {@code @ConfigurationProperties}, so a key omitted from the yml silently keeps its Java default
 * rather than being disabled — the failure mode that made every multibagger composite 15% too
 * high for four months (B-019). Keep the two in sync.
 *
 * <h2>Why {@link #suppressTechnicalExits} defaults to false</h2>
 * Suppressing an exit alert is the <em>removal</em> of a risk control. CLAUDE.md Gotcha 42 fixes
 * the asymmetry: new signals ship shadowed, risk controls ship armed — a bonus being wrong costs
 * a missed opportunity, a risk control being wrong costs capital. The gates that would justify
 * the suppression (capital efficiency, financial quality, earnings consistency) rest on metrics
 * whose measured IC panel runs +0.042 down to −0.009, with ROE, ROA and cash conversion negative
 * (Gotcha 27). With suppression on from day one the counterfactual is never observed and the
 * feature can never be judged, so the first quarter runs in observation mode: alerts fire, and
 * the ones that landed on a core holding are named and persisted as the evidence for the flip.
 */
@Component
@ConfigurationProperties(prefix = "portfolio.core")
@Data
public class CoreHoldingConfig {

    /** Master switch. False = no classification rows written, no email section, exits exactly as today. */
    private boolean enabled = true;

    /**
     * The one behavioural switch. False (default) = observation mode: technical exit alerts on
     * core holdings still fire and are merely reported. True = they are withheld and named.
     */
    private boolean suppressTechnicalExits = false;

    /**
     * How many of the seven gates must carry <em>real evidence</em> before a holding can be CORE.
     * A gate that passes on absent data ({@code PASS_NO_DATA}) is a pass for the fail/pass
     * decision but does not count here — otherwise a holding with no forensic history, no insider
     * filings and no conviction record collects three free passes and reaches CORE on two gates.
     */
    private int minMeasuredGates = 5;

    /** Weekly (Friday) anchors that must agree before a promotion or soft demotion takes effect. */
    private int hysteresisRuns = 2;

    /** Below this many measured durability components the score is null, never a number. */
    private int durabilityMinComponents = 3;

    /** D1: a financial year counts as capital-efficient at or above this post-tax ROCE. */
    private double roceCoreThresholdPercent = 15.0;

    /** G1 for financials (banks/NBFCs), where ROCE on an equity-only base is misleading. */
    private double financialRoeThresholdPercent = 14.0;

    /** G1 for financials — return on assets, the headline metric for a bank. */
    private double financialRoaThresholdPercent = 1.0;

    /** G3: minimum earnings-consistency score. */
    private int consistencyMin = 60;

    /** D5: how far below the running peak counts as a "hiccup" rather than noise. */
    private double drawdownThresholdPercent = 15.0;

    /**
     * D5: only drawdown episodes that closed at least this long ago are scored. The episode a
     * holding is in <em>right now</em> must never lower its durability — that is precisely the
     * moment the tier exists to hold through (R-3).
     */
    private int closedEpisodeMinAgeMonths = 18;

    /** D5: +5 when the prior high was regained within this many months. */
    private int fastRecoveryMonths = 12;

    /** D5: +3 when the prior high was regained within this many months. */
    private int slowRecoveryMonths = 24;

    /** D5: how much daily price history to request per holding. */
    private int priceHistoryYears = 5;
}
