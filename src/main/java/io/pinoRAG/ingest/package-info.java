// Ingestion pipeline.
//
// HTTP entry: POST /v1/collections/{id}/documents (multipart). The
// controller hands off to DocumentUploadService, which persists the file,
// writes the row, and publishes an IngestRequestedEvent inside the request
// transaction.
//
// IngestEventListener consumes the event AFTER_COMMIT and calls @Async on
// IngestPipelineService.startAsync. The pipeline runs on a virtual thread:
// parse - chunk - embed - persist.
//
// Sub-packages:
//   chunking - chunking strategies + selector
//   embed    - embedding providers + selector
//   parse    - format-specific parsers + content-type router
//   write    - JdbcTemplate writers for chunks and vectors
package io.pinoRAG.ingest;
