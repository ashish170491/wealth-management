package com.example.trading.valuation;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for the valuation-gap lens (SPEC §57). Bound from {@code valuation-gap.*}.
 *
 * <p>Every key is listed in {@code application.yml} on purpose: these are
 * {@code @ConfigurationProperties}, so a key omitted from the file keeps its Java default instead
 * of being disabled - the failure that inflated every composite for four months (B-019).
 *
 * <p>The rule constants (hurdle rate, gates, reference multiples) are <b>not</b> here. They live in
 * {@link ValuationGapRules}, where a change fails a test and forces a version bump; a number that
 * can be edited in a config file can drift away from the stored record written under it.
 */
@Configuration
@ConfigurationProperties(prefix = "valuation-gap")
@Data
@Slf4j
public class ValuationGapConfig {

    /**
     * Whether the optional assumption challenger may call a language model. Off by default, and a
     * second gate sits behind it: {@code spring.ai.model.chat} must also name a provider. Either one
     * alone leaves the button answering {@code DISABLED} or {@code AI_NOT_CONFIGURED}.
     */
    private boolean challengeEnabled = false;

    /**
     * Whether the weekday job writes the method-validation snapshot (12:35 MON-FRI). On by default:
     * with it off the evidence clock never starts, and a gap in capture only delays grading, it
     * never corrupts it. Off records a <i>skipped</i> run on the jobs page with this property named.
     */
    private boolean snapshotEnabled = true;

    @PostConstruct
    void announce() {
        log.info("Valuation gap: ruleset {} (fingerprint {}); snapshot job {}; AI challenger {}.",
                ValuationGapRules.RULESET, ValuationGapRules.fingerprint().substring(0, 12),
                snapshotEnabled ? "on (12:35 MON-FRI)" : "off",
                challengeEnabled ? "enabled (still needs spring.ai.model.chat)" : "disabled");
    }
}
