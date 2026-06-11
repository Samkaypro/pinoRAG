package io.pinoRAG.ingest.chunking;

import io.pinoRAG.ingest.IngestProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Character-based sliding window. Token-aware chunking is a follow-up
// once we standardize on a tokenizer.
@Component
public class FixedSizeChunker implements ChunkingStrategy {

    private final int size;
    private final int overlap;

    public FixedSizeChunker(IngestProperties props) {
        this.size = Math.max(1, props.chunkSize());
        this.overlap = Math.max(0, Math.min(props.chunkOverlap(), size - 1));
    }

    @Override
    public String id() {
        return "fixed";
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= size) {
            return List.of(normalized);
        }
        List<String> out = new ArrayList<>();
        int step = size - overlap;
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(start + size, normalized.length());
            out.add(normalized.substring(start, end));
            if (end >= normalized.length()) break;
        }
        return out;
    }
}
