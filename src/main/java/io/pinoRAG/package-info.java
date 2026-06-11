// pinoRAG root.
//
// The package layout is feature-based. Each feature is a self-contained
// vertical slice that owns its controller, service, entity, repository, and
// DTOs. Cross-cutting concerns (auth, tenant scope, rate limiting,
// observability, configuration) sit at the top level next to features.
//
// Feature packages:
//   collection  - CRUD over pino_collections
//   document    - read-side endpoints over pino_documents
//   ingest      - upload + async parse/chunk/embed/persist pipeline
//   chunk       - ChunkEntity + EmbeddingEntity, shared between ingest and retrieval
//   audit       - log entities (ingest_log, query_log, jobs)
//
// Cross-cutting packages:
//   auth        - api key + jwt filters, security config, api_keys entity
//   tenant      - request-scoped TenantContext, Hibernate filter, marker repository
//   ratelimit   - per-IP and per-key Bucket4j interceptors
//   observability - actuator health + future metrics
//   config      - Spring @Configuration that does not belong to a feature
//
// New features get their own top-level package. Do not create layer packages
// like service/, controller/, dto/ at the root; those exist only inside a
// feature and only when a feature gets large enough to need subdivision.
package io.pinoRAG;
