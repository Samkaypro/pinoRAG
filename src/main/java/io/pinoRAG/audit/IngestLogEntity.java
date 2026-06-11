package io.pinoRAG.audit;

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

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "pino_ingest_log")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class IngestLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(nullable = false, length = 20)
    private String stage;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
