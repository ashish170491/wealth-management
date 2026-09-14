package com.example.trading.broker.kite;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class KiteAuthService {

        private final KiteConfig config;
        private final WebClient.Builder webClientBuilder;
        private final GoogleAuthenticator gAuth = new GoogleAuthenticator();

        /**
         * Generates the current TOTP for Zerodha account.
         */
        public String generateTOTP() {
                log.debug("System Time: {} ({})", java.time.LocalDateTime.now(), java.time.ZoneId.systemDefault());
                int code = gAuth.getTotpPassword(config.getTotpSecret());
                String totp = String.format("%06d", code);
                log.debug("Generated TOTP Code: {}***", totp.substring(0, 3));
                return totp;
        }

        /**
         * Exchanges request_token for access_token.
         * This is the standard API way once you have the request_token.
         */
        public Mono<String> exchangeRequestToken(String requestToken) {
                log.info("Exchanging request_token for access_token...");

                // Checksum = sha256(api_key + request_token + api_secret)
                String checksum = org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                                config.getApiKey() + requestToken + config.getApiSecret());

                return webClientBuilder.build()
                                .post()
                                .uri(config.getBaseUrl() + "/session/token")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .body(BodyInserters.fromFormData("api_key", config.getApiKey())
                                                .with("request_token", requestToken)
                                                .with("checksum", checksum))
                                .retrieve()
                                .bodyToMono(Map.class)
                                // An empty body would skip map() entirely and complete empty,
                                // which the caller reads as a successful login with a null token.
                                .switchIfEmpty(Mono.error(new RuntimeException(
                                                "Failed to exchange token: empty response body")))
                                .map(response -> {
                                        if (response == null || !response.containsKey("data")) {
                                                log.error("Token exchange response invalid: {}", response);
                                                throw new RuntimeException(
                                                                "Failed to exchange token: Missing 'data' in response");
                                        }

                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> data = (Map<String, Object>) response.get("data");
                                        String accessToken = (String) data.get("access_token");

                                        if (accessToken == null) {
                                                log.error("Access token not found in response data: {}", data);
                                                throw new RuntimeException(
                                                                "Failed to exchange token: Missing 'access_token'");
                                        }

                                        // Update the config dynamically for the current session
                                        config.setAccessToken(accessToken);

                                        log.info("Successfully obtained and updated new access_token");
                                        return accessToken;
                                });
        }

        /**
         * FULL AUTOMATION (Experimental): Logins and obtains the access_token.
         * This simulates the browser flow using Zerodha's internal login API.
         */
        public Mono<String> automateLogin() {
                log.info("Starting automated login flow for user: {}", config.getUserId());

                WebClient client = webClientBuilder
                                .baseUrl("https://kite.zerodha.com")
                                .defaultHeader("User-Agent",
                                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                .defaultHeader("Referer", "https://kite.zerodha.com/")
                                .defaultHeader("Origin", "https://kite.zerodha.com")
                                .defaultHeader("X-Kite-Version", "3.0.0")
                                .build();

                // 1. Initial Login Request
                return client.post()
                                .uri("/api/login")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .body(BodyInserters.fromFormData("user_id", config.getUserId())
                                                .with("password", config.getPassword()))
                                .exchangeToMono(response -> {
                                        if (response.statusCode().isError()) {
                                                return response.bodyToMono(String.class)
                                                                .defaultIfEmpty("<empty body>")
                                                                .flatMap(body -> {
                                                                        log.error("Login Step 1 failed with status {} and body: {}",
                                                                                        response.statusCode(),
                                                                                        body);
                                                                        return Mono.error(
                                                                                        new RuntimeException(
                                                                                                        "Login Step 1 failed: "
                                                                                                                        + response.statusCode()));
                                                                });
                                        }

                                        var cookies = response.cookies();
                                        return response.bodyToMono(Map.class)
                                                        .switchIfEmpty(Mono.error(new RuntimeException(
                                                                        "Login Step 1 returned an empty body")))
                                                        .map(body -> Map.of("body", body, "cookies", cookies));
                                })
                                .flatMap(loginData -> {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> body = (Map<String, Object>) loginData.get("body");
                                        @SuppressWarnings("unchecked")
                                        var cookieMap = (org.springframework.util.MultiValueMap<String, org.springframework.http.ResponseCookie>) loginData
                                                        .get("cookies");

                                        // Create a MUTABLE copied map since response.cookies() is often unmodifiable
                                        org.springframework.util.MultiValueMap<String, org.springframework.http.ResponseCookie> cookies = new org.springframework.util.LinkedMultiValueMap<>(
                                                        cookieMap);

                                        String requestId = (String) ((Map<?, ?>) body.get("data")).get("request_id");
                                        String totp = generateTOTP();
                                        log.info("Login step 1 successful. Request ID: {}. Submitting TOTP...",
                                                        requestId);

                                        // 2. Submit TOTP
                                        return client.post()
                                                        .uri("/api/twofa")
                                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                                        .cookies(c -> cookies.forEach((k, v) -> v.forEach(
                                                                        cookie -> c.add(k, cookie.getValue()))))
                                                        .body(BodyInserters.fromFormData("user_id", config.getUserId())
                                                                        .with("request_id", requestId)
                                                                        .with("twofa_value", totp)
                                                                        .with("skip_session", "true"))
                                                        .exchangeToMono(response -> {
                                                                if (response.statusCode().isError()) {
                                                                        return response.bodyToMono(String.class)
                                                                                        .defaultIfEmpty("<empty body>")
                                                                                        .flatMap(errBody -> {
                                                                                                log.error("TOTP Step 2 failed with status {} and body: {}",
                                                                                                                response.statusCode(),
                                                                                                                errBody);
                                                                                                return Mono.error(
                                                                                                                new RuntimeException(
                                                                                                                                "TOTP Step 2 failed: "
                                                                                                                                                + response.statusCode()));
                                                                                        });
                                                                }
                                                                // Merge new cookies
                                                                var newCookies = response.cookies();
                                                                cookies.putAll(newCookies);
                                                                return Mono.just(cookies);
                                                        });
                                })
                                .flatMap(allCookies -> {
                                        log.info("TOTP step 2 successful. Initiating Kite Connect Authorization...");

                                        // 3. Authorize Kite Connect App
                                        // skip_auth=1 tells Zerodha to skip the "Authorize" button if previously
                                        // authorized
                                        String initialConnectUrl = "https://kite.zerodha.com/connect/login?v=3&api_key="
                                                        + config.getApiKey() + "&skip_auth=1";
                                        return followRedirects(client, initialConnectUrl, allCookies, 5);
                                })
                                .flatMap(this::exchangeRequestToken);
        }

        /**
         * Recursively follows redirects to find the request_token.
         */
        private Mono<String> followRedirects(WebClient client, String url,
                        org.springframework.util.MultiValueMap<String, org.springframework.http.ResponseCookie> cookies,
                        int maxRedirects) {
                if (maxRedirects <= 0) {
                        return Mono.error(new RuntimeException("Too many redirects while following login flow"));
                }

                log.debug("Following redirect to: {}", url);

                return client.get()
                                .uri(url)
                                .cookies(c -> cookies
                                                .forEach((k, v) -> v.forEach(cookie -> c.add(k, cookie.getValue()))))
                                .exchangeToMono(response -> {
                                        if (response.statusCode().is3xxRedirection()) {
                                                var headers = response.headers().asHttpHeaders();
                                                var location = headers.getLocation();
                                                if (location != null) {
                                                        String locStr = location.toString();

                                                        // Check if we found the token
                                                        if (locStr.contains("request_token=")) {
                                                                String requestToken = locStr.split("request_token=")[1]
                                                                                .split("&")[0];
                                                                log.info("Found request_token in redirect: {}...",
                                                                                requestToken.substring(0, 5));
                                                                return Mono.just(requestToken);
                                                        }

                                                        // Update cookies if any new ones arrive
                                                        var newCookies = response.cookies();
                                                        cookies.putAll(newCookies);

                                                        // Handle relative vs absolute redirect URLs
                                                        String nextUrl = locStr;
                                                        if (!locStr.startsWith("http")) {
                                                                nextUrl = "https://kite.zerodha.com"
                                                                                + (locStr.startsWith("/") ? "" : "/")
                                                                                + locStr;
                                                        }

                                                        return followRedirects(client, nextUrl, cookies,
                                                                        maxRedirects - 1);
                                                }
                                        } else if (response.statusCode().is2xxSuccessful()) {
                                                return response.bodyToMono(String.class)
                                                                .defaultIfEmpty("")
                                                                .flatMap(body -> {
                                                                        if (body.contains("request_token=")) {
                                                                                // Sometimes its in the body if
                                                                                // Javascript redirect is used
                                                                                String requestToken = body.split(
                                                                                                "request_token=")[1]
                                                                                                .split("\"")[0].split(
                                                                                                                "'")[0]
                                                                                                .split("&")[0];
                                                                                return Mono.just(requestToken);
                                                                        }
                                                                        log.error("Reached 200 OK but request_token not found. Body snippet: {}",
                                                                                        body.length() > 500 ? body
                                                                                                        .substring(0, 500)
                                                                                                        : body);
                                                                        return Mono.error(
                                                                                        new RuntimeException(
                                                                                                        "Auth flow reached dead end at 200 OK without token"));
                                                                });
                                        }

                                        return response.bodyToMono(String.class)
                                                        .defaultIfEmpty("<empty body>")
                                                        .flatMap(body -> {
                                                                log.error("Auth flow failed with status {}. Body snippet: {}",
                                                                                response.statusCode(),
                                                                                body.length() > 500 ? body.substring(0,
                                                                                                500) : body);
                                                                return Mono.error(
                                                                                new RuntimeException(
                                                                                                "Auth flow failed with status "
                                                                                                                + response.statusCode()));
                                                        });
                                });
        }
}
