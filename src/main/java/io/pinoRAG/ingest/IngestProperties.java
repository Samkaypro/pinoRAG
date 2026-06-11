package io.pinoRAG.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pinorag.ingest")
public record IngestProperties(
        String uploadDir,
        long maxUploadBytes,
        int chunkSize,
        int chunkOverlap,
        int embeddingBatchSize
) {
}
