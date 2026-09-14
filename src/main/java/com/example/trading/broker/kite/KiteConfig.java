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

    @NotBlank(message = "Base URL is required")
    private String baseUrl = "https://api.kite.trade";

    @NotBlank(message = "User ID is required for automation")
    private String userId;

    @NotBlank(message = "Password is required for automation")
    private String password;

    @NotBlank(message = "TOTP Secret is required for automation")
    private String totpSecret;
}
