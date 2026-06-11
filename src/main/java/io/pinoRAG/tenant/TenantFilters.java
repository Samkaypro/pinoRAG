package io.pinoRAG.tenant;

// Constants used by the Hibernate filter that scopes every read by tenant_id.
// Keep this small: name + parameter name only.
public final class TenantFilters {

    public static final String TENANT_FILTER = "tenantFilter";
    public static final String TENANT_PARAM  = "tenantId";

    private TenantFilters() {}
}
