package io.pinoRAG.llm;

// Streaming sink for LlmClient. The orchestrator passes one of these in
// and the client calls onToken for each chunk it produces, then exactly
// one of onComplete or onError.
public interface TokenConsumer {

    void onToken(String text);

    void onComplete();

    void onError(Throwable error);
}
