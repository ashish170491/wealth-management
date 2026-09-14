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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI Chat Completions implementation. Compatible with any OpenAI-spec endpoint
 * (Azure OpenAI, local LLMs) via AiConfig.baseUrl override.
 */
@Slf4j
public class OpenAiService implements AiService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final AiConfig config;
    private final WebClient webClient;

    public OpenAiService(AiConfig config) {
        this.config = config;
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.openai.com";
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("OpenAI service initialized (model={}, baseUrl={})", config.getModel(), baseUrl);
    }

    @Override
    public String analyze(String systemPrompt, String userPrompt, String modelOverride) {
        String model = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride : config.getModel();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));
            if (isReasoningModel(model)) {
                body.put("max_completion_tokens", config.getMaxTokens());
            } else {
                body.put("max_tokens", config.getMaxTokens());
                body.put("temperature", config.getTemperature());
            }

            Map<String, Object> response = webClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(MAP_TYPE)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofSeconds(8))
                            .filter(OpenAiService::isTransient)
                            .doBeforeRetry(sig -> log.warn("AI: OpenAI retry {} after {}",
                                    sig.totalRetries() + 1, sig.failure().getClass().getSimpleName()))
                            .onRetryExhaustedThrow((spec, sig) -> sig.failure()))
                    .block(Duration.ofSeconds(config.getTimeoutSeconds()));

            if (response != null && response.containsKey("choices")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
                if (!choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");
                    log.debug("AI: OpenAI response received ({} chars, model={})",
                            content != null ? content.length() : 0, model);
                    return content != null ? content.trim() : "";
                }
            }

            log.warn("AI: Empty response from OpenAI (model={})", model);
            return "";
        } catch (WebClientResponseException e) {
            log.error("AI: OpenAI HTTP {} (model={}): {}",
                    e.getStatusCode(), model, e.getResponseBodyAsString());
            return "";
        } catch (Exception e) {
            log.error("AI: OpenAI call failed (model={}): {}", model, e.getMessage());
            return "";
        }
    }

    @Override
    public boolean isAvailable() {
        return config.isEnabled() && config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    private static boolean isReasoningModel(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.startsWith("gpt-5");
    }

    private static boolean isTransient(Throwable ex) {
        if (ex instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return status == 429 || status == 502 || status == 503 || status == 504;
        }
        return ex instanceof TimeoutException || ex instanceof ConnectException;
    }
}
