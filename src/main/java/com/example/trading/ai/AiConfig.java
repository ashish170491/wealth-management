package com.example.trading.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai")
@Data
public class AiConfig {
    private boolean enabled = false;
    private String provider = "openai";       // "openai", "claude", "none"
    private String apiKey;
    private String model = "gpt-4o-mini";     // default cost-effective model
    private String baseUrl;                    // null = use provider default
    private int timeoutSeconds = 30;
    private int maxTokens = 1024;
    private double temperature = 0.3;
}
