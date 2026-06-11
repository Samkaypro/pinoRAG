package io.pinoRAG.tenant;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

// Enables the Hibernate tenant filter on the current session with the
// caller's tenantId. Call this once per service method that touches
// tenant-scoped entities. The filter is auto-disabled when the session
// closes at end of request.
@Component
public class TenantFilterEnabler {

    private final EntityManager entityManager;
    private final TenantContext tenantContext;

    public TenantFilterEnabler(EntityManager entityManager, TenantContext tenantContext) {
        this.entityManager = entityManager;
        this.tenantContext = tenantContext;
    }

    public void enableForCurrentRequest() {
        Long tenantId = tenantContext.requireTenantId();
        Session session = entityManager.unwrap(Session.class);
        if (session.getEnabledFilter(TenantFilters.TENANT_FILTER) == null) {
            session.enableFilter(TenantFilters.TENANT_FILTER)
                    .setParameter(TenantFilters.TENANT_PARAM, tenantId);
        }
    }
}
