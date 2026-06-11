package io.pinoRAG.retrieval;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Dispatcher that picks a Retriever by mode. Mirrors EmbedderSelector and
// LlmClientSelector in shape so configuration drives selection rather
// than a switch buried inside a service.
@Component
public class Retrievers {

    private static final Logger log = LoggerFactory.getLogger(Retrievers.class);

    private final List<Retriever> available;
    private final Map<RetrievalMode, Retriever> byMode = new EnumMap<>(RetrievalMode.class);

    public Retrievers(List<Retriever> available) {
        this.available = available;
    }

    @PostConstruct
    void index() {
        for (Retriever r : available) {
            Retriever prior = byMode.put(r.mode(), r);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two Retrievers claim mode " + r.mode() + ": "
                                + prior.getClass().getName() + " and " + r.getClass().getName());
            }
        }
        // Fail fast at boot, not on the first request, if a mode lacks an impl.
        for (RetrievalMode mode : RetrievalMode.values()) {
            if (!byMode.containsKey(mode)) {
                throw new IllegalStateException(
                        "No Retriever registered for mode=" + mode
                                + "; registered=" + byMode.keySet());
            }
        }
        log.info("Registered retrievers: {}", byMode.keySet());
    }

    public Retriever forMode(RetrievalMode mode) {
        Retriever r = byMode.get(mode);
        if (r == null) {
            throw new IllegalStateException(
                    "No Retriever registered for mode=" + mode + "; registered=" + byMode.keySet());
        }
        return r;
    }
}
