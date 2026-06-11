// Collection feature.
//
// A Collection groups documents that share an embedding model and chunking
// strategy. Every collection belongs to exactly one tenant.
//
// Owns: pino_collections entity, repository, service, REST controller,
// response DTO. All queries go through Hibernate's tenant filter via the
// TenantScopedRepository marker.
package io.pinoRAG.collection;
