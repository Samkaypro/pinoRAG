package io.pinoRAG.llm;

public interface LlmClient {

    String id();

    void stream(LlmRequest request, TokenConsumer consumer);
}
