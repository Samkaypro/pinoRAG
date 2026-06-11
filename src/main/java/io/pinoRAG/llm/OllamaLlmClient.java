package io.pinoRAG.llm;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// Bridges Spring AI's OllamaChatModel.stream(Prompt) Flux into our callback
// shape. Bound to the concrete class so it does not collide with the
// OpenAI chat bean when both starters are present.
@Component
@ConditionalOnClass(OllamaChatModel.class)
@ConditionalOnProperty(name = "pinorag.llm.id", havingValue = "ollama")
@ConditionalOnBean(OllamaChatModel.class)
public class OllamaLlmClient implements LlmClient {

    private final OllamaChatModel chat;

    public OllamaLlmClient(OllamaChatModel chat) {
        this.chat = chat;
    }

    @Override
    public String id() {
        return "ollama";
    }

    @Override
    public void stream(LlmRequest request, TokenConsumer consumer) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(request.systemPrompt()),
                new UserMessage(request.userPrompt())));
        chat.stream(prompt).subscribe(
                resp -> {
                    String chunk = resp.getResult().getOutput().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        consumer.onToken(chunk);
                    }
                },
                consumer::onError,
                consumer::onComplete);
    }
}
