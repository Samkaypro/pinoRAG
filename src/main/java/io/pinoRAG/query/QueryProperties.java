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
        long sseTimeoutMillis
) {

    public enum FallbackMode {
        // Refuse to answer when no chunk passes the score threshold.
        REFUSE,
        // Answer using only the LLM (no context). For permissive deployments.
        LLM_ONLY,
        // Return the configured fallbackMessage verbatim and stop.
        MESSAGE
    }
}
