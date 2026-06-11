package io.pinoRAG.ingest;

// Surfaced to API clients via DocumentResponse.error so callers see a stable
// short code, never raw exception text or stack-trace-derived paths.
public enum IngestErrorCode {
    STORAGE_FAILED,
    PARSE_FAILED,
    CHUNK_FAILED,
    EMBED_FAILED,
    PERSIST_FAILED,
    UNKNOWN
}
