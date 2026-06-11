package io.pinoRAG.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Reciprocal Rank Fusion. For each contributing retriever, each chunk
// gets 1 / (k + rank) added to its accumulated score (rank is 1-based).
// k = 60 is the value popularized in the original RRF paper and is the
// industry default; it dampens long-tail noise from each retriever while
// preserving sharp differentiation at the head.
@Component
public class RrfMerger {

    public static final int DEFAULT_K = 60;

    public List<ScoredChunk> merge(List<List<ScoredChunk>> lists, int limit) {
        return merge(lists, DEFAULT_K, limit);
    }

    public List<ScoredChunk> merge(List<List<ScoredChunk>> lists, int k, int limit) {
        if (lists == null || lists.isEmpty() || limit <= 0) {
            return List.of();
        }
        // First seen wins for the source metadata (document name, body).
        // Each retriever returns the same chunk_id -> identical row in the DB,
        // so picking either is fine.
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, ScoredChunk> sources = new HashMap<>();

        for (List<ScoredChunk> list : lists) {
            if (list == null) continue;
            for (int i = 0; i < list.size(); i++) {
                ScoredChunk c = list.get(i);
                int rank = i + 1;
                scores.merge(c.chunkId(), 1.0 / (k + rank), Double::sum);
                sources.putIfAbsent(c.chunkId(), c);
            }
        }

        List<Map.Entry<Long, Double>> ordered = new ArrayList<>(scores.entrySet());
        ordered.sort(Map.Entry.<Long, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry::getKey));

        List<ScoredChunk> out = new ArrayList<>(Math.min(ordered.size(), limit));
        for (int i = 0; i < Math.min(ordered.size(), limit); i++) {
            var entry = ordered.get(i);
            ScoredChunk src = sources.get(entry.getKey());
            out.add(new ScoredChunk(
                    src.chunkId(),
                    src.documentId(),
                    src.documentName(),
                    src.body(),
                    entry.getValue()));
        }
        return out;
    }
}
