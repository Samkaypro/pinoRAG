package io.pinoRAG.llm;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// Bridges Spring AI's OpenAiChatModel.stream(Prompt) Flux into our callback
// shape. Active when:
//   - pinorag.llm.id = openai
//   - the OpenAiChatModel class is on the classpath
//   - Spring AI autoconfigured the bean (requires spring.ai.openai.api-key)
@Component
@ConditionalOnClass(OpenAiChatModel.class)
@ConditionalOnProperty(name = "pinorag.llm.id", havingValue = "openai")
@ConditionalOnBean(OpenAiChatModel.class)
public class OpenAILlmClient implements LlmClient {

    private final OpenAiChatModel chat;

    public OpenAILlmClient(OpenAiChatModel chat) {
        this.chat = chat;
    }

    @Override
    public String id() {
        return "openai";
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
