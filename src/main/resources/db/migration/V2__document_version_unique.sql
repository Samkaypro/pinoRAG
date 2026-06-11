-- Stops two concurrent uploads of the same source name from racing to
-- the same version number. Without this constraint the deprecate-then-insert
-- flow could produce two active rows at (tenant, collection, source_uri, v).

CREATE UNIQUE INDEX uq_pino_documents_tenant_coll_source_version
    ON pino_documents (tenant_id, collection_id, source_uri, version);
