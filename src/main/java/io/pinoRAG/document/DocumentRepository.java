package io.pinoRAG.document;

import io.pinoRAG.document.DocumentEntity;
import io.pinoRAG.tenant.TenantScopedRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository
        extends JpaRepository<DocumentEntity, Long>,
                TenantScopedRepository<DocumentEntity, Long> {

    @Query("SELECT d FROM DocumentEntity d " +
            "WHERE d.tenantId = :tenantId AND d.id = :id")
    Optional<DocumentEntity> findByIdForTenant(@Param("tenantId") Long tenantId,
                                               @Param("id") Long id);

    @Query("SELECT d FROM DocumentEntity d " +
            "WHERE d.tenantId = :tenantId AND d.collectionId = :collectionId " +
            "AND d.sourceUri = :sourceUri AND d.status <> 'DEPRECATED'")
    List<DocumentEntity> findActiveVersions(@Param("tenantId") Long tenantId,
                                            @Param("collectionId") Long collectionId,
                                            @Param("sourceUri") String sourceUri);
}
