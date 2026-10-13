package com.example.trading.broker.kite;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "broker.kite")
@Data
@Validated
public class KiteConfig {
    @NotBlank(message = "API Key is required")
    private String apiKey;

    @NotBlank(message = "API Secret is required")
    private String apiSecret;

    @NotBlank(message = "Access Token is required")
    private String accessToken;

    /**
     * When this app obtained {@link #accessToken}; null when the token came from configuration
     * and its issue time is unknown. Not a property anyone sets: {@code KiteAuthService} stamps
     * it on login, and {@link KiteSessionClock} reads it to tell a token from yesterday (dead
     * since 06:00) from today's (B-171).
     */
    private volatile java.time.Instant accessTokenIssuedAt;

    @NotBlank(message = "Base URL is required")
    private String baseUrl = "https://api.kite.trade";

    @NotBlank(message = "User ID is required for automation")
    private String userId;

    @NotBlank(message = "Password is required for automation")
    private String password;

    @NotBlank(message = "TOTP Secret is required for automation")
    private String totpSecret;
}
