package com.example.trading.broker.kite;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
@Slf4j
@RequiredArgsConstructor
public class TokenManagementService {

    private final KiteAuthService authService;
    private final KiteConfig config;
    private volatile boolean loginInProgress = false;

    /**
     * Zerodha sessions expire every morning. Token is also refreshed on startup via
     * {@link #onStartup()} — this cron covers mid-day recovery if the startup refresh
     * failed. App only runs during market hours, so we schedule within that window.
     */
    @Scheduled(cron = "0 45 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyTokenRefresh() {
        log.info("Starting scheduled daily token refresh...");
        refreshAccessToken();
    }

    /**
     * Also attempt to refresh token at startup if current one is placeholder or
     * missing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (config.getAccessToken() == null || config.getAccessToken().contains("placeholder")) {
            log.info("No valid access token found at startup. Initiating automated login...");
            refreshAccessToken();
        }
    }

    public void refreshAccessToken() {
        if (loginInProgress) {
            log.warn("Login already in progress. Skipping duplicate request.");
            return;
        }
        
        loginInProgress = true;
        authService.automateLogin()
                // Any auth step whose HTTP response carries an empty body completes the chain
                // empty rather than erroring, which used to look like a successful refresh
                // holding a null token. Fail loudly, upstream of the retry, so it is retried.
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Login flow completed without returning an access token")))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(retrySignal -> 
                            log.warn("Authentication failed. Retry attempt {} of 3. Error: {}", 
                                retrySignal.totalRetries() + 1, 
                                retrySignal.failure().getMessage())))
                .doOnSuccess(token -> log.info("Token refresh successful. Token: {}...{}", 
                        token.substring(0, Math.min(4, token.length())),
                        token.length() > 8 ? token.substring(token.length() - 4) : ""))
                // doFinally, not doOnSuccess/doOnError: a cancelled subscription would
                // otherwise latch the flag true and block every later refresh for the JVM's life.
                .doFinally(signal -> loginInProgress = false)
                // Both consumers are required. Without an error consumer the failure reaches
                // Reactor's default onErrorDropped handler, which dumps a raw stack trace at
                // ERROR immediately after we have already logged the readable message.
                .subscribe(
                        token -> { },
                        error -> log.error("Token refresh failed after all retry attempts. Type: {}. Message: {}. Application will continue with invalid token - trading operations will fail until next login attempt.",
                                error.getClass().getSimpleName(), error.getMessage()));
    }
    
    /**
     * Check if the current access token is valid (not placeholder).
     */
    public boolean hasValidToken() {
        String token = config.getAccessToken();
        return token != null && !token.isEmpty() && !token.contains("placeholder");
    }
}
