package io.pinoRAG.api;

import io.pinoRAG.domain.repository.CollectionRepository;
import io.pinoRAG.tenant.TenantContext;
import io.pinoRAG.tenant.TenantFilterEnabler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CollectionService {

    private final CollectionRepository collections;
    private final TenantContext tenant;
    private final TenantFilterEnabler filterEnabler;

    public CollectionService(CollectionRepository collections,
                             TenantContext tenant,
                             TenantFilterEnabler filterEnabler) {
        this.collections = collections;
        this.tenant = tenant;
        this.filterEnabler = filterEnabler;
    }

    @Transactional(readOnly = true)
    public List<CollectionResponse> listForCaller() {
        filterEnabler.enableForCurrentRequest();
        return collections.findAllForTenant(tenant.requireTenantId())
                .stream()
                .map(CollectionResponse::from)
                .toList();
    }
}
