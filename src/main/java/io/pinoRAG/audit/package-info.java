// Audit log entities.
//
// Append-only tables used by features for diagnostics and forensic queries:
//   IngestLogEntity - one row per ingest pipeline stage
//   QueryLogEntity  - one row per /v1/query call
//   JobEntity       - reserved for an async job queue
//
// No service or controller in this package yet. Features write rows
// directly via JdbcTemplate so an audit failure can never roll back the
// real transaction.
package io.pinoRAG.audit;
