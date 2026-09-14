package com.example.trading.portfolio.conviction;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Conviction-drift thresholds (SPEC.md §6). */
@Component
@ConfigurationProperties(prefix = "portfolio.conviction")
@Data
public class ConvictionConfig {
    /** Score drop (points) at which thesis becomes "under review". */
    private double yellowDriftThreshold = 15.0;

    /** Score drop (points) at which thesis is "broken". */
    private double redDriftThreshold = 25.0;
}
