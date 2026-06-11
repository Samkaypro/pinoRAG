package io.pinoRAG.api;

import io.pinoRAG.domain.entity.CollectionEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CollectionResponse(
        Long id,
        UUID uuid,
        String name,
        String embeddingModel,
        String chunkingStrategy,
        OffsetDateTime createdAt
) {

    public static CollectionResponse from(CollectionEntity e) {
        return new CollectionResponse(
                e.getId(),
                e.getUuid(),
                e.getName(),
                e.getEmbeddingModel(),
                e.getChunkingStrategy(),
                e.getCreatedAt());
    }
}
