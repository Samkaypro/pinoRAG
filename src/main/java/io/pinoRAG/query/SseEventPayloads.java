package io.pinoRAG.query;

import io.pinoRAG.retrieval.ScoredChunk;

import java.util.List;

// Wire shapes for the SSE events. The event name (the "event:" line) is set
// by the controller; these records are the JSON bodies.
public final class SseEventPayloads {

    private SseEventPayloads() {}

    public record Status(String phase) {}

    public record Citation(Long chunkId, Long documentId, String documentName, Double score) {
        public static Citation of(ScoredChunk c) {
            return new Citation(c.chunkId(), c.documentId(), c.documentName(), c.score());
        }
    }

    public record Token(String text) {}

    public record Done(long latencyMs, List<Long> usedChunkIds) {}

    public record ErrorPayload(String code, String message) {}
}
