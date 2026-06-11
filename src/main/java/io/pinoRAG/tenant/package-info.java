// Tenant scope - the security spine.
//
// TenantContext is the request-scoped holder for the verified tenantId.
// TenantEntity carries the @FilterDef that declares the Hibernate filter;
// every other tenant-scoped entity (collections, documents, chunks,
// embeddings, audit logs, api keys) references it with @Filter.
// TenantFilterEnabler turns it on for the current request.
//
// Owns: pino_tenants entity, TenantScopedRepository marker, the filter
// definition.
package io.pinoRAG.tenant;
