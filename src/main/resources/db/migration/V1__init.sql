-- pinoRAG initial schema. Every table is tenant-scoped via tenant_id FK.
-- The vector dimension is fixed at 1536 for v1 (OpenAI text-embedding-3-small).
-- Per-collection dimensions are a v1.1 follow-up.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE pino_tenants (
    id         BIGSERIAL    PRIMARY KEY,
    uuid       UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    name       VARCHAR(200) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- API keys store only the prefix and a hash of the secret.
CREATE TABLE pino_api_keys (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES pino_tenants(id) ON DELETE CASCADE,
    prefix        VARCHAR(16)  NOT NULL UNIQUE,
    hashed_secret VARCHAR(255) NOT NULL,
    scopes        TEXT[]       NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ
);
CREATE INDEX idx_pino_api_keys_tenant ON pino_api_keys (tenant_id);

CREATE TABLE pino_collections (
    id                BIGSERIAL    PRIMARY KEY,
    uuid              UUID         NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    tenant_id         BIGINT       NOT NULL REFERENCES pino_tenants(id) ON DELETE CASCADE,
    name              VARCHAR(200) NOT NULL,
    embedding_model   VARCHAR(100) NOT NULL,
    chunking_strategy VARCHAR(50)  NOT NULL DEFAULT 'recursive',
    settings          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE pino_documents (
    id            BIGSERIAL     PRIMARY KEY,
    uuid          UUID          NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    tenant_id     BIGINT        NOT NULL REFERENCES pino_tenants(id) ON DELETE CASCADE,
    collection_id BIGINT        NOT NULL REFERENCES pino_collections(id) ON DELETE CASCADE,
    source_uri    VARCHAR(2000) NOT NULL,
    mime_type     VARCHAR(127),
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    version       INT           NOT NULL DEFAULT 1,
    owner_subject VARCHAR(255),
    group_ids     TEXT[]        NOT NULL DEFAULT '{}',
    is_public     BOOLEAN       NOT NULL DEFAULT FALSE,
    error         TEXT,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_pino_documents_tenant_collection ON pino_documents (tenant_id, collection_id);
CREATE INDEX idx_pino_documents_tenant_status     ON pino_documents (tenant_id, status);

CREATE TABLE pino_chunks (
    id             BIGSERIAL   PRIMARY KEY,
    uuid           UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    tenant_id      BIGINT      NOT NULL REFERENCES pino_tenants(id) ON DELETE CASCADE,
    collection_id  BIGINT      NOT NULL REFERENCES pino_collections(id) ON DELETE CASCADE,
    document_id    BIGINT      NOT NULL REFERENCES pino_documents(id) ON DELETE CASCADE,
    ordinal        INT         NOT NULL,
    body           TEXT        NOT NULL,
    body_encrypted BYTEA,
    tokens         INT,
    metadata       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    body_tsv       TSVECTOR    GENERATED ALWAYS AS (to_tsvector('english', body)) STORED,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, ordinal)
);
CREATE INDEX idx_pino_chunks_tenant_collection ON pino_chunks (tenant_id, collection_id);
CREATE INDEX idx_pino_chunks_document          ON pino_chunks (document_id);
CREATE INDEX idx_pino_chunks_tsv               ON pino_chunks USING GIN (body_tsv);

CREATE TABLE pino_embeddings (
    id         BIGSERIAL    PRIMARY KEY,
    chunk_id   BIGINT       NOT NULL UNIQUE REFERENCES pino_chunks(id) ON DELETE CASCADE,
    model      VARCHAR(100) NOT NULL,
    dim        INT          NOT NULL,
    embedding  vector(1536) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- ivfflat needs ANALYZE to be useful. The list count is a starting hint.
CREATE INDEX idx_pino_embeddings_vec ON pino_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE TABLE pino_query_log (
    id                  BIGSERIAL   PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL REFERENCES pino_tenants(id) ON DELETE CASCADE,
    api_key_id          BIGINT      REFERENCES pino_api_keys(id) ON DELETE SET NULL,
    query_hash          CHAR(64)    NOT NULL,
    retrieved_chunk_ids BIGINT[]    NOT NULL DEFAULT '{}',
    latency_ms          INT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pino_query_log_tenant_time ON pino_query_log (tenant_id, created_at DESC);

CREATE TABLE pino_ingest_log (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES pino_tenants(id) ON DELETE CASCADE,
    document_id BIGINT      REFERENCES pino_documents(id) ON DELETE CASCADE,
    stage       VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    latency_ms  INT,
    message     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pino_ingest_log_tenant_time ON pino_ingest_log (tenant_id, created_at DESC);

CREATE TABLE pino_jobs (
    id         BIGSERIAL   PRIMARY KEY,
    uuid       UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    tenant_id  BIGINT      NOT NULL REFERENCES pino_tenants(id) ON DELETE CASCADE,
    type       VARCHAR(50) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payload    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    attempts   INT         NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pino_jobs_tenant_status ON pino_jobs (tenant_id, status);

-- Keep updated_at fresh on mutable rows.
CREATE OR REPLACE FUNCTION pino_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pino_documents_updated_at
    BEFORE UPDATE ON pino_documents
    FOR EACH ROW EXECUTE FUNCTION pino_set_updated_at();

CREATE TRIGGER pino_jobs_updated_at
    BEFORE UPDATE ON pino_jobs
    FOR EACH ROW EXECUTE FUNCTION pino_set_updated_at();
