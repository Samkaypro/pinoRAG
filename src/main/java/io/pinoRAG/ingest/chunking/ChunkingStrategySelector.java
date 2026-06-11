package io.pinoRAG.ingest.chunking;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChunkingStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(ChunkingStrategySelector.class);

    private final List<ChunkingStrategy> available;
    private final String configuredId;
    private ChunkingStrategy active;

    public ChunkingStrategySelector(List<ChunkingStrategy> available,
                                    @Value("${pinorag.chunker.id:fixed}") String configuredId) {
        this.available = available;
        this.configuredId = configuredId;
    }

    @PostConstruct
    void resolve() {
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "No ChunkingStrategy beans on the classpath. " +
                            "Check that io.pinoRAG.ingest.chunk components are scanned.");
        }
        this.active = available.stream()
                .filter(c -> c.id().equalsIgnoreCase(configuredId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No ChunkingStrategy registered for pinorag.chunker.id=" + configuredId
                                + "; available=" + available.stream().map(ChunkingStrategy::id).toList()));
        log.info("Active chunker: id={}", active.id());
    }

    public ChunkingStrategy active() {
        return active;
    }
}
