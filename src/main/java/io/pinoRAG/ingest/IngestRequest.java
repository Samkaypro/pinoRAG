package io.pinoRAG.ingest;

import java.util.UUID;

public record IngestRequest(
        Long documentId,
        Long tenantId,
        Long collectionId,
        UUID documentUuid,
        int version,
        String mimeType
) {
}
