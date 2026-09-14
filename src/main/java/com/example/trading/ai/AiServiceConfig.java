package com.example.trading.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean factory that creates the appropriate AiService implementation
 * based on the configured provider in application.yml.
 */
@Configuration
@Slf4j
public class AiServiceConfig {

    @Bean
    public AiService aiService(AiConfig aiConfig) {
        if (!aiConfig.isEnabled()) {
            log.info("AI service disabled");
            return new NoOpAiService();
        }

        return switch (aiConfig.getProvider().toLowerCase()) {
            case "openai" -> {
                log.info("AI service: OpenAI (model={})", aiConfig.getModel());
                yield new OpenAiService(aiConfig);
            }
            case "claude", "anthropic" -> {
                log.info("AI service: Claude (model={})", aiConfig.getModel());
                yield new ClaudeAiService(aiConfig);
            }
            default -> {
                log.warn("AI service: Unknown provider '{}', disabling", aiConfig.getProvider());
                yield new NoOpAiService();
            }
        };
    }
}
