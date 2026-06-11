package io.pinoRAG.ingest.embed;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

// Picks the active Embedder by id from pinorag.embedder.id. Fails fast with
// the full diagnostic so misconfiguration is obvious at boot.
@Component
public class EmbedderSelector {

    private static final Logger log = LoggerFactory.getLogger(EmbedderSelector.class);
    private static final List<String> KNOWN_IDS = List.of("fake", "ollama");

    private final List<Embedder> available;
    private final String configuredId;
    private Embedder active;

    public EmbedderSelector(List<Embedder> available,
                            @Value("${pinorag.embedder.id:fake}") String configuredId) {
        this.available = available;
        this.configuredId = configuredId;
    }

    @PostConstruct
    void resolve() {
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "No Embedder beans on the classpath. Configured pinorag.embedder.id=" + configuredId
                            + ". Each impl is gated by @ConditionalOnProperty + (for ollama) @ConditionalOnBean. "
                            + "Either flip the property to a built-in id (" + KNOWN_IDS + ") or ensure the upstream "
                            + "model bean is available.");
        }
        this.active = available.stream()
                .filter(e -> e.id().equalsIgnoreCase(configuredId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No Embedder registered for pinorag.embedder.id=" + configuredId
                                + "; available=" + available.stream().map(Embedder::id).toList()
                                + "; known=" + KNOWN_IDS));
        log.info("Active embedder: id={} dim={}", active.id(), active.dimensions());
    }

    public Embedder active() {
        return active;
    }
}
