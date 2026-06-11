package io.pinoRAG.retrieval;

public record ScoredChunk(
        Long chunkId,
        Long documentId,
        String documentName,
        String body,
        double score
) {
}
