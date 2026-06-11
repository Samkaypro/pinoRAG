package io.pinoRAG.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.UUID;

@Getter
@Setter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "pino_documents")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uuid;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "collection_id", nullable = false)
    private Long collectionId;

    @Column(name = "source_uri", nullable = false, length = 2000)
    private String sourceUri;

    @Column(name = "mime_type", length = 127)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentStatus status;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "owner_subject", length = 255)
    private String ownerSubject;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "group_ids", nullable = false, columnDefinition = "text[]")
    private String[] groupIds;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
