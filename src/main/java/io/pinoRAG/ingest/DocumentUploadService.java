package io.pinoRAG.ingest;

import io.pinoRAG.collection.CollectionEntity;
import io.pinoRAG.document.DocumentEntity;
import io.pinoRAG.document.DocumentStatus;
import io.pinoRAG.collection.CollectionRepository;
import io.pinoRAG.document.DocumentRepository;
import io.pinoRAG.tenant.TenantContext;
import io.pinoRAG.tenant.TenantFilterEnabler;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentUploadService {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);

    private final CollectionRepository collections;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final TenantContext tenant;
    private final TenantFilterEnabler filterEnabler;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public DocumentUploadService(CollectionRepository collections,
                                 DocumentRepository documents,
                                 DocumentStorage storage,
                                 TenantContext tenant,
                                 TenantFilterEnabler filterEnabler,
                                 JdbcTemplate jdbc,
                                 ApplicationEventPublisher events) {
        this.collections = collections;
        this.documents = documents;
        this.storage = storage;
        this.tenant = tenant;
        this.filterEnabler = filterEnabler;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public DocumentEntity uploadMultipart(Long collectionId, MultipartFile file) {
        Long tenantId = tenant.requireTenantId();
        filterEnabler.enableForCurrentRequest();

        CollectionEntity collection = collections.findByIdForTenant(tenantId, collectionId)
                .orElseThrow(() -> new EntityNotFoundException("collection"));

        String filename = FilenameSanitizer.sanitize(file.getOriginalFilename());
        String mimeType = file.getContentType();

        int version = nextVersionAndDeprecateOlder(tenantId, collection.getId(), filename);

        UUID docUuid = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        Long docId;
        try {
            docId = jdbc.queryForObject(
                    "INSERT INTO pino_documents " +
                            "(uuid, tenant_id, collection_id, source_uri, mime_type, " +
                            " status, version, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?) RETURNING id",
                    Long.class,
                    docUuid, tenantId, collection.getId(), filename, mimeType,
                    version, now, now);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent upload of the same source raced us to this version.
            throw new ConcurrentUploadException(filename);
        }

        Path stored = storage.persist(tenantId, collection.getId(), docUuid, version, file);
        log.info("Stored upload for doc {} v{} at {}", docId, version, stored);

        // Publish AFTER the transaction commits so the async pipeline can
        // safely read the row. The listener calls @Async.
        events.publishEvent(new IngestRequestedEvent(new IngestRequest(
                docId, tenantId, collection.getId(), docUuid, version, mimeType)));

        return documents.findByIdForTenant(tenantId, docId).orElseThrow();
    }

    private int nextVersionAndDeprecateOlder(Long tenantId, Long collectionId, String sourceUri) {
        List<DocumentEntity> active = documents.findActiveVersions(tenantId, collectionId, sourceUri);
        int max = active.stream().mapToInt(DocumentEntity::getVersion).max().orElse(0);
        for (DocumentEntity prior : active) {
            prior.setStatus(DocumentStatus.DEPRECATED);
        }
        if (!active.isEmpty()) {
            documents.saveAll(active);
        }
        return max + 1;
    }

    public static class ConcurrentUploadException extends RuntimeException {
        public ConcurrentUploadException(String filename) {
            super("Concurrent upload detected for " + filename);
        }
    }
}
