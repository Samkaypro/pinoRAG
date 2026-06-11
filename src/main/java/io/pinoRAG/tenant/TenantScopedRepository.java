package io.pinoRAG.tenant;

// Marker for repositories whose every read must be scoped by tenant_id.
// Used together with Hibernate filters defined in domain.entity and the
// TenantFilterEnabler that activates them per request.
public interface TenantScopedRepository<T, ID> {
}
