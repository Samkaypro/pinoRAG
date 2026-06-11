package io.pinoRAG.llm;

public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        int maxTokens,
        double temperature
) {
}
