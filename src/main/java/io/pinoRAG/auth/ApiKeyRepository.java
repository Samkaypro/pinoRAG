package io.pinoRAG.auth;

import io.pinoRAG.auth.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    // Native query because the auth filter looks up the key BEFORE TenantContext
    // is set, so the Hibernate tenant filter would block this read.
    @Query(value = "SELECT * FROM pino_api_keys WHERE prefix = :prefix",
           nativeQuery = true)
    Optional<ApiKeyEntity> findByPrefixUnscoped(@Param("prefix") String prefix);
}
