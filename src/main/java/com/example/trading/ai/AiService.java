package com.example.trading.ai;

/**
 * Provider-agnostic AI service interface for report enrichment.
 * Supports OpenAI, Claude, and other LLM providers interchangeably.
 */
public interface AiService {

    /**
     * Send a prompt with system context and get back AI-generated text.
     * Implementations MUST return "" on failure so callers can gracefully degrade.
     *
     * @param systemPrompt  role/persona instructions
     * @param userPrompt    the actual request with data context
     * @param modelOverride model name to use for this call only (null = use configured default)
     * @return AI-generated response text, or empty string on failure
     */
    String analyze(String systemPrompt, String userPrompt, String modelOverride);

    default String analyze(String systemPrompt, String userPrompt) {
        return analyze(systemPrompt, userPrompt, null);
    }

    default String analyzeForReport(String reportContext) {
        return analyze(
            "You are an expert Indian stock market analyst. Provide concise, actionable insights in 3-5 bullet points. " +
            "Focus on what matters for trading decisions today. Use plain text, no markdown.",
            reportContext
        );
    }

    /**
     * Check if AI service is available and configured.
     */
    boolean isAvailable();

    /**
     * Format AI response as an HTML section for email reports.
     * Returns empty string if AI is unavailable or response is empty.
     */
    default String buildAiHtmlSection(String sectionTitle, String systemPrompt, String userPrompt) {
        return buildAiHtmlSection(sectionTitle, systemPrompt, userPrompt, null);
    }

    default String buildAiHtmlSection(String sectionTitle, String systemPrompt, String userPrompt, String modelOverride) {
        if (!isAvailable()) return "";
        try {
            String response = analyze(systemPrompt, userPrompt, modelOverride);
            if (response == null || response.isBlank()) return "";
            return formatAiResponseAsHtml(sectionTitle, response);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Convert plain text AI response into a styled HTML block for email reports.
     */
    static String formatAiResponseAsHtml(String title, String aiResponse) {
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:2px;border-radius:10px;margin:15px 0;\">");
        html.append("<div style=\"background:#fff;border-radius:8px;padding:15px;\">");
        html.append("<div style=\"font-size:11px;color:#999;margin-bottom:8px;\">&#129302; Powered by AI Analysis</div>");
        if (title != null && !title.isEmpty()) {
            html.append("<div style=\"font-weight:700;color:#333;margin-bottom:8px;\">").append(title).append("</div>");
        }
        html.append("<ul style=\"margin:0;padding-left:20px;color:#333;\">");
        for (String line : aiResponse.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            trimmed = trimmed.replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+[.):]\\s*", "");
            if (!trimmed.isEmpty()) {
                html.append("<li style=\"margin-bottom:6px;\">").append(trimmed).append("</li>");
            }
        }
        html.append("</ul></div></div>");
        return html.toString();
    }
}
