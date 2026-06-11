package io.pinoRAG.document;

import io.pinoRAG.document.DocumentEntity;
import io.pinoRAG.document.DocumentRepository;
import io.pinoRAG.tenant.TenantContext;
import io.pinoRAG.tenant.TenantFilterEnabler;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

    private final DocumentRepository documents;
    private final TenantContext tenant;
    private final TenantFilterEnabler filterEnabler;
    private final JdbcTemplate jdbc;

    public DocumentService(DocumentRepository documents,
                           TenantContext tenant,
                           TenantFilterEnabler filterEnabler,
                           JdbcTemplate jdbc) {
        this.documents = documents;
        this.tenant = tenant;
        this.filterEnabler = filterEnabler;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DocumentResponse getForCaller(Long id) {
        Long tenantId = tenant.requireTenantId();
        filterEnabler.enableForCurrentRequest();

        DocumentEntity doc = documents.findByIdForTenant(tenantId, id)
                .orElseThrow(() -> new EntityNotFoundException("document"));

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pino_chunks WHERE tenant_id = ? AND document_id = ?",
                Long.class, tenantId, id);
        return DocumentResponse.from(doc, count == null ? 0L : count);
    }
}
