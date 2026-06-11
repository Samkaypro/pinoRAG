package io.pinoRAG.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pinorag.query")
public record QueryProperties(
        int topK,
        double minScore,
        int contextTokenBudget,
        int maxAnswerTokens,
        double temperature,
        FallbackMode fallbackMode,
        String fallbackMessage,
        long sseTimeoutMillis,
        // Bound the LLM_ONLY fallback so a hung provider does not pin a
        // virtual thread until the SseEmitter timeout fires.
        long llmTimeoutMillis
) {

    public enum FallbackMode {
        REFUSE,
        LLM_ONLY,
        MESSAGE
    }
}
