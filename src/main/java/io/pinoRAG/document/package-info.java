// Document feature - read side.
//
// Read-only view over pino_documents: status, version, chunk count, error
// code. Upload + the async ingest pipeline live in the ingest/ package.
// Both packages share the DocumentEntity defined here.
package io.pinoRAG.document;
