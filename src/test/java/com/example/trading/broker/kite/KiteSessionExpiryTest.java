package com.example.trading.broker.kite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins B-171: a token from yesterday is dead after 06:00, Kite's 403 TokenException is noticed,
 * and noticing it starts a login without turning every request into one.
 *
 * <p>The first case is the incident itself: an app restarted at 22:53 on 12 Oct still held that
 * evening's token when the 09:20 holdings sync ran on 13 Oct.
 */
class KiteSessionExpiryTest {

    private static Instant ist(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute).atZone(KiteSessionClock.IST).toInstant();
    }

    @Test
    @DisplayName("A token issued at 22:53 is dead at 09:20 the next morning (the 13 Oct incident)")
    void eveningTokenIsDeadNextMorning() {
        assertThat(KiteSessionClock.isExpired(ist(12, 22, 53), ist(13, 9, 20))).isTrue();
    }

    @Test
    @DisplayName("A token issued this morning is live until 06:00 tomorrow, and dead from 06:00")
    void morningTokenLivesUntilTomorrowSix() {
        Instant issued = ist(13, 9, 1);
        assertThat(KiteSessionClock.isExpired(issued, ist(13, 15, 30))).isFalse();
        assertThat(KiteSessionClock.isExpired(issued, ist(14, 5, 59))).isFalse();
        assertThat(KiteSessionClock.isExpired(issued, ist(14, 6, 0))).isTrue();
    }

    @Test
    @DisplayName("A token issued between midnight and 06:00 still dies at that same morning's 06:00")
    void smallHoursTokenDiesAtSix() {
        assertThat(KiteSessionClock.isExpired(ist(13, 5, 30), ist(13, 5, 59))).isFalse();
        assertThat(KiteSessionClock.isExpired(ist(13, 5, 30), ist(13, 6, 1))).isTrue();
    }

    @Test
    @DisplayName("An unknown issue time is not 'expired' - a configured token may well be live")
    void unknownIsNotExpired() {
        assertThat(KiteSessionClock.isExpired(null, ist(13, 9, 20))).isFalse();
    }

    // ------------------------------------------------------------------ broker client

    private static KiteBrokerClient kiteAnswering(HttpStatus status, String body, KiteConfig config,
                                                  List<KiteTokenRejectedEvent> events) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request ->
                Mono.just(ClientResponse.create(status)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(body).build()));
        KiteBrokerClient kite = new KiteBrokerClient(config, builder);
        kite.setEventPublisher(e -> events.add((KiteTokenRejectedEvent) e));
        return kite;
    }

    @Test
    @DisplayName("Kite's 403 TokenException is reported as a dead session (it used to be ignored: only 401 was)")
    void tokenExceptionIsReported() {
        List<KiteTokenRejectedEvent> events = Collections.synchronizedList(new ArrayList<>());
        KiteBrokerClient kite = kiteAnswering(HttpStatus.FORBIDDEN,
                "{\"status\":\"error\",\"message\":\"Incorrect `api_key` or `access_token`.\",\"data\":null,\"error_type\":\"TokenException\"}",
                new KiteConfig(), events);
        assertThatThrownBy(() -> kite.getQuote("NSE:TCS"));
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).reason()).contains("TokenException");
    }

    @Test
    @DisplayName("Any other failure is not mistaken for a dead session")
    void otherErrorsAreNot() {
        List<KiteTokenRejectedEvent> events = Collections.synchronizedList(new ArrayList<>());
        KiteBrokerClient kite = kiteAnswering(HttpStatus.BAD_REQUEST,
                "{\"status\":\"error\",\"message\":\"Invalid instrument\",\"error_type\":\"InputException\"}",
                new KiteConfig(), events);
        assertThatThrownBy(() -> kite.getQuote("NSE:TCS"));
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("A token stamped before today's 06:00 is reported before Kite even answers")
    void staleTokenIsReportedProactively() {
        List<KiteTokenRejectedEvent> events = Collections.synchronizedList(new ArrayList<>());
        KiteConfig config = new KiteConfig();
        config.setAccessTokenIssuedAt(Instant.now().minusSeconds(26 * 3600));   // always before the last 06:00
        KiteBrokerClient kite = kiteAnswering(HttpStatus.OK,
                "{\"status\":\"success\",\"data\":{\"NSE:TCS\":{\"last_price\":1}}}", config, events);
        kite.getQuote("NSE:TCS");
        assertThat(events).anySatisfy(e -> assertThat(e.reason()).contains("06:00"));
    }

    @Test
    @DisplayName("The cause chain is searched: a wrapped TokenException still counts")
    void wrappedRejectionCounts() {
        RuntimeException wrapped = new RuntimeException("Instrument token resolution failed for NSE:X",
                new RuntimeException("Quote fetch failed: {\"error_type\":\"TokenException\"}"));
        assertThat(KiteBrokerClient.isTokenRejection(wrapped)).isTrue();
        assertThat(KiteBrokerClient.isTokenRejection(new RuntimeException("timeout"))).isFalse();
    }

    // ------------------------------------------------------------------ re-login

    @Test
    @DisplayName("A rejection logs in at once, but a second within five minutes does not start another login")
    void reloginIsRateLimited() {
        KiteAuthService auth = mock(KiteAuthService.class);
        when(auth.automateLogin()).thenReturn(Mono.just("fresh-token"));
        TokenManagementService tokens = new TokenManagementService(auth, new KiteConfig());

        Instant t0 = ist(13, 9, 20);
        assertThat(tokens.handleRejection("TokenException", t0)).isTrue();
        assertThat(tokens.handleRejection("TokenException", t0.plusSeconds(60))).isFalse();
        assertThat(tokens.handleRejection("TokenException",
                t0.plus(TokenManagementService.RELOGIN_COOLDOWN))).isTrue();
        verify(auth, times(2)).automateLogin();
    }

    @Test
    @DisplayName("hasValidToken refuses yesterday's token even though one is present")
    void hasValidTokenChecksAge() {
        KiteConfig config = new KiteConfig();
        config.setAccessToken("abc123");
        TokenManagementService tokens = new TokenManagementService(mock(KiteAuthService.class), config);
        assertThat(tokens.hasValidToken()).isTrue();                    // unknown age: trusted
        config.setAccessTokenIssuedAt(Instant.now().minusSeconds(26 * 3600));
        assertThat(tokens.hasValidToken()).isFalse();
        config.setAccessTokenIssuedAt(Instant.now());
        assertThat(tokens.hasValidToken()).isTrue();
    }
}
