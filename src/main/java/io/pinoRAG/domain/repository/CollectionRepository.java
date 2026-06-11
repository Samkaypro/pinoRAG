package io.pinoRAG.domain.repository;

import io.pinoRAG.domain.entity.CollectionEntity;
import io.pinoRAG.tenant.repository.TenantScopedRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CollectionRepository
        extends JpaRepository<CollectionEntity, Long>,
                TenantScopedRepository<CollectionEntity, Long> {

    @Query("SELECT c FROM CollectionEntity c WHERE c.tenantId = :tenantId ORDER BY c.id")
    List<CollectionEntity> findAllForTenant(@Param("tenantId") Long tenantId);

    @Query("SELECT c FROM CollectionEntity c WHERE c.tenantId = :tenantId AND c.id = :id")
    Optional<CollectionEntity> findByIdForTenant(@Param("tenantId") Long tenantId,
                                                 @Param("id") Long id);
}
