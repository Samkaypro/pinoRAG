package io.pinoRAG.llm;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// Bridges Spring AI's ChatModel.stream(Prompt) Flux into our callback shape.
// Only active when pinorag.llm.id=ollama AND a ChatModel bean exists.
@Component
@ConditionalOnProperty(name = "pinorag.llm.id", havingValue = "ollama")
@ConditionalOnBean(ChatModel.class)
public class OllamaLlmClient implements LlmClient {

    private final ChatModel chat;

    public OllamaLlmClient(ChatModel chat) {
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
