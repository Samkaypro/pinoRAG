package io.pinoRAG.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Deterministic streamer for tests and offline boot. Active when
// pinorag.llm.id = fake. Emits one token per whitespace-separated word from
// a canned answer so SSE tests can assert event ordering without an LLM.
@Component
@ConditionalOnProperty(name = "pinorag.llm.id", havingValue = "fake")
public class FakeLlmClient implements LlmClient {

    private static final String CANNED_ANSWER =
            "Based on the provided context, here is the answer.";

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public void stream(LlmRequest request, TokenConsumer consumer) {
        try {
            for (String word : CANNED_ANSWER.split(" ")) {
                consumer.onToken(word + " ");
            }
            consumer.onComplete();
        } catch (RuntimeException ex) {
            consumer.onError(ex);
        }
    }
}
