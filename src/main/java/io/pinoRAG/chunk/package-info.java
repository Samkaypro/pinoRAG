// Chunk and embedding storage.
//
// Owns: pino_chunks and pino_embeddings entities. Used by ingest writers
// (Phase 3) and by retrieval readers (Phase 4 and later). The actual vector
// column is not mapped to JPA - it is read and written via JdbcTemplate
// with CAST(? AS vector) so we do not need a pgvector JDBC dependency.
package io.pinoRAG.chunk;
