-- Caller identity within a tenant. An API key now optionally identifies
-- a subject (typically a user id or a service name) and zero or more
-- groups. Retrievers use these to enforce per-document ACLs against
-- pino_documents.owner_subject and pino_documents.group_ids.
--
-- Both columns are nullable / empty-default so existing keys keep working
-- as "tenant-wide reader" until an operator sets them.

ALTER TABLE pino_api_keys
    ADD COLUMN subject VARCHAR(255),
    ADD COLUMN groups  TEXT[] NOT NULL DEFAULT '{}';
