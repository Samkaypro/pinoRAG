package io.pinoRAG.tenant.repository;

// Small helper that fails fast when a caller forgets to pass a tenant id.
// Use at the top of any repository method that accepts tenantId.
public final class Tenants {

    private Tenants() {}

    public static Long require(Long tenantId, String operation) {
        if (tenantId == null || tenantId <= 0) {
            throw new MissingTenantException(operation);
        }
        return tenantId;
    }
}
