package com.example.trading.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Anthropic Claude Messages API implementation.
 */
@Slf4j
public class ClaudeAiService implements AiService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final AiConfig config;
    private final WebClient webClient;

    public ClaudeAiService(AiConfig config) {
        this.config = config;
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.anthropic.com";
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", config.getApiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("Claude AI service initialized (model={}, baseUrl={})", config.getModel(), baseUrl);
    }

    @Override
    public String analyze(String systemPrompt, String userPrompt, String modelOverride) {
        String model = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : config.getModel();
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", config.getMaxTokens(),
                    "temperature", config.getTemperature(),
                    "system", systemPrompt,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))
            );

            Map<String, Object> response = webClient.post()
                    .uri("/v1/messages")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(MAP_TYPE)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofSeconds(8))
                            .filter(ClaudeAiService::isTransient)
                            .doBeforeRetry(sig -> log.warn("AI: Claude retry {} after {}",
                                    sig.totalRetries() + 1, sig.failure().getClass().getSimpleName()))
                            .onRetryExhaustedThrow((spec, sig) -> sig.failure()))
                    .block(Duration.ofSeconds(config.getTimeoutSeconds()));

            if (response != null && response.containsKey("content")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
                if (!content.isEmpty()) {
                    String text = (String) content.get(0).get("text");
                    log.debug("AI: Claude response received ({} chars, model={})",
                            text != null ? text.length() : 0, model);
                    return text != null ? text.trim() : "";
                }
            }

            log.warn("AI: Empty response from Claude (model={})", model);
            return "";
        } catch (WebClientResponseException e) {
            log.error("AI: Claude HTTP {} (model={}): {}",
                    e.getStatusCode(), model, e.getResponseBodyAsString());
            return "";
        } catch (Exception e) {
            log.error("AI: Claude call failed (model={}): {}", model, e.getMessage());
            return "";
        }
    }

    @Override
    public boolean isAvailable() {
        return config.isEnabled() && config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    private static boolean isTransient(Throwable ex) {
        if (ex instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 429 || status == 502 || status == 503 || status == 504 || status == 529;
        }
        return ex instanceof TimeoutException || ex instanceof ConnectException;
    }
}
