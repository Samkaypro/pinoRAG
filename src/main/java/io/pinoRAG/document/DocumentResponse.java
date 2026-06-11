package io.pinoRAG.document;

import io.pinoRAG.document.DocumentEntity;
import io.pinoRAG.document.DocumentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentResponse(
        Long id,
        UUID uuid,
        Long collectionId,
        String sourceUri,
        String mimeType,
        DocumentStatus status,
        Integer version,
        Long chunkCount,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static DocumentResponse from(DocumentEntity e, long chunkCount) {
        return new DocumentResponse(
                e.getId(),
                e.getUuid(),
                e.getCollectionId(),
                e.getSourceUri(),
                e.getMimeType(),
                e.getStatus(),
                e.getVersion(),
                chunkCount,
                e.getError(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
