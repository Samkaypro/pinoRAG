// JdbcTemplate writers for chunks and embeddings.
//
// Bypass JPA on the write path so we can use pgvector's CAST(? AS vector)
// directly and avoid Hibernate dirty-checking on the large insert volume
// of an ingest run.
package io.pinoRAG.ingest.write;
