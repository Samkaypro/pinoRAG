package io.pinoRAG.tenant.repository;

// Marker for repositories whose every read must be scoped by tenant_id.
// In phase 2 a TenantContext provider will make this implicit. For now it
// reminds reviewers that any new query against a pino_* table needs a
// tenant_id predicate.
public interface TenantScopedRepository<T, ID> {
}
