// Defines the global Hibernate tenant filter. Every entity in this package
// that holds a tenant_id column applies @Filter(name="tenantFilter") so reads
// are physically scoped. The filter is enabled per request by TenantFilterAspect.
@FilterDef(
        name = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = Long.class)
)
package io.pinoRAG.domain.entity;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
