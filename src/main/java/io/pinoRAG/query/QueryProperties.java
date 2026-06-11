package io.pinoRAG.query;

import io.pinoRAG.retrieval.RetrievalMode;
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
        long llmTimeoutMillis,
        // Default retrieval mode. Per-request override allowed via QueryRequest.
        RetrievalMode retrievalMode
) {

    public enum FallbackMode {
        REFUSE,
        LLM_ONLY,
        MESSAGE
    }
}
