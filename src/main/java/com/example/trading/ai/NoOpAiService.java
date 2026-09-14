package com.example.trading.ai;

import lombok.extern.slf4j.Slf4j;

/**
 * No-op implementation when AI is disabled or provider is not configured.
 * Returns empty strings so callers can gracefully degrade.
 */
@Slf4j
public class NoOpAiService implements AiService {

    @Override
    public String analyze(String systemPrompt, String userPrompt, String modelOverride) {
        return "";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
