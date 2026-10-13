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
import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class TokenManagementService {

    private final KiteAuthService authService;
    private final KiteConfig config;
    private volatile boolean loginInProgress = false;

    /**
     * The shortest gap between two logins the broker client may trigger (B-171). A rejected token
     * re-logs in at once; a rejection that keeps coming back (wrong credentials, a Kite outage)
     * must not turn every paced request into a login attempt.
     */
    static final Duration RELOGIN_COOLDOWN = Duration.ofMinutes(5);

    /** When the last login was started, by any path; null before the first. */
    private volatile Instant lastLoginAttempt;

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

    /**
     * The broker client found the session dead (B-171): Kite answered {@code TokenException}, or
     * the token predates the last 06:00 cut-off. Log in again now rather than waiting for the
     * 09:45 recovery job. Before this, a process that ran across midnight (an evening deploy)
     * failed every Kite call from 06:00 until 09:45, and the 09:20 holdings sync with it.
     */
    @EventListener
    public void onTokenRejected(KiteTokenRejectedEvent event) {
        handleRejection(event.reason(), Instant.now());
    }

    /** Package-private so the cooldown can be tested without a clock. */
    boolean handleRejection(String reason, Instant now) {
        Instant last = lastLoginAttempt;
        if (last != null && Duration.between(last, now).compareTo(RELOGIN_COOLDOWN) < 0) {
            log.debug("Kite session rejected ({}), but a login started {} s ago - not retrying yet",
                    reason, Duration.between(last, now).toSeconds());
            return false;
        }
        log.warn("Kite session is dead ({}). Logging in again now instead of waiting for the 09:45 recovery (B-171).",
                reason);
        refreshAccessToken(now);
        return true;
    }

    public void refreshAccessToken() {
        refreshAccessToken(Instant.now());
    }

    private void refreshAccessToken(Instant now) {
        if (loginInProgress) {
            log.warn("Login already in progress. Skipping duplicate request.");
            return;
        }
        
        loginInProgress = true;
        lastLoginAttempt = now;
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
     * Check if the current access token is usable: present, not a placeholder, and not issued
     * before the last 06:00 cut-off. The last test was missing until B-171, so a process that ran
     * overnight reported yesterday's dead token as valid and let guarded jobs run on it.
     */
    public boolean hasValidToken() {
        String token = config.getAccessToken();
        return token != null && !token.isEmpty() && !token.contains("placeholder")
                && !KiteSessionClock.isExpired(config.getAccessTokenIssuedAt(), Instant.now());
    }
}
