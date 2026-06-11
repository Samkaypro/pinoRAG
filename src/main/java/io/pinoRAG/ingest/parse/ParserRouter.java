package io.pinoRAG.ingest.parse;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ParserRouter {

    private final List<DocumentParser> parsers;
    private final TikaParser fallback;

    public ParserRouter(List<DocumentParser> parsers, TikaParser fallback) {
        this.parsers = parsers;
        this.fallback = fallback;
    }

    public DocumentParser route(String mimeType) {
        // First match wins. TikaParser claims everything via supports(), so a
        // more specific parser must come before it. We sort here so injection
        // order doesn't matter.
        return parsers.stream()
                .filter(p -> p != fallback)
                .filter(p -> p.supports(mimeType))
                .findFirst()
                .orElse(fallback);
    }
}
