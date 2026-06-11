package io.pinoRAG.llm;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

// Picks the active LlmClient by id from pinorag.llm.id. Fails fast at boot
// if nothing matches so misconfiguration is loud, not a runtime surprise
// the first time someone queries.
@Component
public class LlmClientSelector {

    private static final Logger log = LoggerFactory.getLogger(LlmClientSelector.class);
    private static final List<String> KNOWN_IDS = List.of("fake", "ollama", "openai");

    private final List<LlmClient> available;
    private final String configuredId;
    private LlmClient active;

    public LlmClientSelector(List<LlmClient> available,
                             @Value("${pinorag.llm.id:fake}") String configuredId) {
        this.available = available;
        this.configuredId = configuredId;
    }

    @PostConstruct
    void resolve() {
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "No LlmClient beans on the classpath. Configured pinorag.llm.id=" + configuredId
                            + ". Each impl is gated by @ConditionalOnProperty and (for ollama) "
                            + "@ConditionalOnBean(ChatModel.class). Flip the property to a built-in id "
                            + KNOWN_IDS + " or ensure the upstream model bean is wired.");
        }
        this.active = available.stream()
                .filter(c -> c.id().equalsIgnoreCase(configuredId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No LlmClient registered for pinorag.llm.id=" + configuredId
                                + "; available=" + available.stream().map(LlmClient::id).toList()
                                + "; known=" + KNOWN_IDS));
        log.info("Active llm client: id={}", active.id());
    }

    public LlmClient active() {
        return active;
    }
}
