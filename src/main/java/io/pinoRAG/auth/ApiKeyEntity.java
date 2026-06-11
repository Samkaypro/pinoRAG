package io.pinoRAG.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

// Lookup by prefix happens BEFORE the tenant filter is enabled, so the API key
// auth path uses a native query that ignores the filter.
@Getter
@Setter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "pino_api_keys")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 16, unique = true)
    private String prefix;

    @Column(name = "hashed_secret", nullable = false, length = 255)
    private String hashedSecret;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scopes", nullable = false, columnDefinition = "text[]")
    private String[] scopes;

    // Caller identity for per-document ACL enforcement. Both fields are
    // nullable / empty: an unset subject + empty groups means the key has
    // no per-document restrictions beyond the tenant filter.
    @Column(name = "subject", length = 255)
    private String subject;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "groups", nullable = false, columnDefinition = "text[]")
    private String[] groups;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;
}
