package io.pinoRAG.tenant;

// Fail-fast helper for any code path that must have a verified caller.
public final class Tenants {

    private Tenants() {}

    public static Long require(Long tenantId, String operation) {
        if (tenantId == null || tenantId <= 0) {
            throw new MissingTenantException(operation);
        }
        return tenantId;
    }
}
