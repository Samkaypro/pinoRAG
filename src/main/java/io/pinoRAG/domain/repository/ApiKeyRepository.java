package io.pinoRAG.domain.repository;

import io.pinoRAG.domain.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    // Native query because the auth filter looks up the key BEFORE TenantContext
    // is set, so the Hibernate tenant filter would block this read.
    @Query(value = "SELECT * FROM pino_api_keys WHERE prefix = :prefix",
           nativeQuery = true)
    Optional<ApiKeyEntity> findByPrefixUnscoped(@Param("prefix") String prefix);

    @Modifying
    @Transactional
    @Query(value = "UPDATE pino_api_keys SET last_used_at = :ts WHERE id = :id",
           nativeQuery = true)
    void touchLastUsed(@Param("id") Long id, @Param("ts") OffsetDateTime timestamp);
}
