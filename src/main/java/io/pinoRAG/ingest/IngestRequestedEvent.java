package io.pinoRAG.ingest;

// Fired from the upload transaction; consumed AFTER_COMMIT so the async
// pipeline never reads a row that the publishing transaction hasn't yet
// flushed. The event payload is the same IngestRequest the pipeline
// already knows how to run.
public record IngestRequestedEvent(IngestRequest request) {
}
