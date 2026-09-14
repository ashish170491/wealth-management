package com.example.trading.portfolio.core;

import com.example.trading.portfolio.core.CoreDto.CoreTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The behavioural overlay (SPEC §35.5) — the only place a tier changes what the app <em>does</em>.
 *
 * <p>Applied at report and alert time, never inside scoring or ML training data. The stored
 * {@code holdings.recommendation} column is untouched, so {@code HoldingsTrainingDataCollector}
 * keeps learning from the raw signal and no model label moves because a tier moved.
 *
 * <h2>Two modes, and why the default is the timid one</h2>
 * <ul>
 *   <li><b>Observation</b> ({@code suppress-technical-exits=false}, shipped default) — nothing is
 *       withheld. Alerts fire and dedup exactly as they do today; the overlay only <em>records</em>
 *       which of them landed on a core holding, and the email names them. This is the evidence the
 *       flag flip will be argued from.</li>
 *   <li><b>Suppression</b> ({@code true}) — the four technical alerts are withheld on core
 *       holdings but still named in the footer.</li>
 * </ul>
 *
 * <h2>The dedup hazard this is built around (CLAUDE.md Gotcha 19)</h2>
 * {@code ExitTimingAlertService.evaluateHolding()} used to mutate its dedup set inline at every
 * call site, so any caller that evaluated an alert consumed the day's slot for it whether or not
 * the alert was ever sent. Filtering suppressed alerts <em>after</em> evaluation would therefore
 * silently disarm them for the rest of the day — the failure that ran unnoticed for weeks the
 * first time. Suppression mode instead evaluates with dedup off and records a key only for what it
 * actually sends. Observation mode keeps the ordinary dedup path, because those alerts really are
 * sent and would otherwise repeat at 10:00, 12:00 and 14:00.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoreOverlayService {

    private final CoreHoldingConfig config;
    private final CoreClassificationService classificationService;

    /**
     * The four price-driven alerts. DEEP_LOSS is deliberately absent: a core holding down 15%+ on a
     * bearish trend is exactly the case where the thesis deserves a look, so it is retained in both
     * modes and merely re-titled.
     */
    private static final List<String> TECHNICAL_ALERTS = List.of(
            "NEAR_RESISTANCE", "RSI_OVERBOUGHT", "BROKE_SUPPORT", "MOMENTUM_REVERSAL");

    /** Short cache so one email build does not re-query per holding, but a same-day
     *  critical demotion still reaches the overlay within minutes. */
    private static final Duration TIER_CACHE_TTL = Duration.ofMinutes(5);

    private volatile Map<String, CoreTier> tierCache = Map.of();
    private volatile Instant tierCacheAt = Instant.EPOCH;

    public boolean enabled() {
        return config.isEnabled();
    }

    /** True when the one behavioural switch is on. False for the whole observation quarter. */
    public boolean suppressionEnabled() {
        return config.isEnabled() && config.isSuppressTechnicalExits();
    }

    public static boolean isTechnicalAlert(String alertType) {
        return TECHNICAL_ALERTS.contains(alertType);
    }

    /** The tier the overlay acts on. UNCLASSIFIED when nothing has been classified yet. */
    public CoreTier tierOf(String symbol) {
        return tiers().getOrDefault(symbol, CoreTier.UNCLASSIFIED);
    }

    /** True for CORE and CORE_WATCH — the two tiers "hold through the noise" applies to. */
    public boolean isProtected(String symbol) {
        return config.isEnabled() && tierOf(symbol).isProtected();
    }

    /**
     * What the email should print in place of a technical SELL / BOOK_PROFIT.
     *
     * <p>Presentational only. The stored recommendation is unchanged, and the underlying label is
     * kept in parentheses so nothing is concealed from the reader.
     */
    public String displayRecommendation(String symbol, String storedRecommendation) {
        if (!isProtected(symbol)) return storedRecommendation;
        if (storedRecommendation == null) return "HOLD_CORE";
        return switch (storedRecommendation) {
            case "SELL", "STRONG_SELL", "BOOK_PROFIT" -> "HOLD_CORE (" + storedRecommendation + ")";
            default -> storedRecommendation;
        };
    }

    /** Record what fired on a core holding today, for the R-1 evidence trail. */
    public void recordObservedAlerts(String symbol, List<String> alertTypes) {
        try {
            classificationService.recordObservedAlerts(symbol, alertTypes);
        } catch (Exception e) {
            log.warn("Could not record observed core alerts for {}: {}", symbol, e.getMessage());
        }
    }

    private Map<String, CoreTier> tiers() {
        if (!config.isEnabled()) return Map.of();
        Instant now = Instant.now();
        if (Duration.between(tierCacheAt, now).compareTo(TIER_CACHE_TTL) > 0) {
            try {
                tierCache = classificationService.effectiveTiers();
            } catch (Exception e) {
                // An empty map means "no holding is protected", which degrades to today's exact
                // behaviour rather than to silence. That is the safe direction for a risk control.
                log.warn("Core tier lookup failed - the overlay will treat every holding as "
                        + "unprotected, i.e. exactly as before this feature: {}", e.getMessage());
                tierCache = Map.of();
            }
            tierCacheAt = now;
        }
        return tierCache;
    }

    /** Drop the cache so a freshly-written classification is visible at once. */
    public void invalidate() {
        tierCacheAt = Instant.EPOCH;
    }
}
