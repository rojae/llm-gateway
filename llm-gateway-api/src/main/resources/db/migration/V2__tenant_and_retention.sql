-- The placeholder is validated as an identifier before Flyway is invoked.
-- Existing unversioned installations require an explicit legacyTenantId.
ALTER TABLE jev_analytics.requests ADD COLUMN tenant_id TEXT;
ALTER TABLE jev_analytics.contents ADD COLUMN tenant_id TEXT;
ALTER TABLE jev_analytics.checkpoints ADD COLUMN tenant_id TEXT;
UPDATE jev_analytics.requests SET tenant_id = '${legacyTenantId}';
UPDATE jev_analytics.contents SET tenant_id = '${legacyTenantId}';
UPDATE jev_analytics.checkpoints SET tenant_id = '${legacyTenantId}';
ALTER TABLE jev_analytics.requests ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE jev_analytics.contents ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE jev_analytics.checkpoints ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE jev_analytics.requests DROP CONSTRAINT requests_pkey;
ALTER TABLE jev_analytics.requests ADD PRIMARY KEY (tenant_id, request_id);
ALTER TABLE jev_analytics.contents DROP CONSTRAINT contents_pkey;
ALTER TABLE jev_analytics.contents ADD PRIMARY KEY (tenant_id, request_id, phase);
ALTER TABLE jev_analytics.checkpoints DROP CONSTRAINT checkpoints_pkey;
ALTER TABLE jev_analytics.checkpoints ADD PRIMARY KEY (tenant_id, source_id, kind, file_id);

DROP INDEX jev_analytics.requests_started_idx;
DROP INDEX jev_analytics.requests_traffic_started_idx;
DROP INDEX jev_analytics.requests_source_started_idx;
CREATE INDEX requests_tenant_started_idx ON jev_analytics.requests (tenant_id, started_at DESC, request_id DESC);
CREATE INDEX requests_tenant_source_started_idx ON jev_analytics.requests (tenant_id, source, started_at DESC);
CREATE INDEX requests_tenant_traffic_started_idx ON jev_analytics.requests (tenant_id, traffic, started_at DESC);
CREATE INDEX requests_tenant_key_started_idx ON jev_analytics.requests (tenant_id, key_id, started_at DESC);
CREATE INDEX requests_tenant_session_started_idx ON jev_analytics.requests (tenant_id, session_id, started_at DESC);
CREATE INDEX contents_tenant_ingested_idx ON jev_analytics.contents (tenant_id, ingested_at);
CREATE INDEX checkpoints_tenant_updated_idx ON jev_analytics.checkpoints (tenant_id, updated_at DESC);

-- Identity rows outlive payload retention. This prevents replay from recreating purged data.
CREATE TABLE jev_analytics.request_identities (
 tenant_id TEXT NOT NULL, request_id TEXT NOT NULL,
 first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id, request_id)
);
CREATE TABLE jev_analytics.content_identities (
 tenant_id TEXT NOT NULL, request_id TEXT NOT NULL, phase TEXT NOT NULL,
 first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id, request_id, phase)
);
INSERT INTO jev_analytics.request_identities (tenant_id, request_id, first_seen_at)
 SELECT tenant_id, request_id, ingested_at FROM jev_analytics.requests;
INSERT INTO jev_analytics.content_identities (tenant_id, request_id, phase, first_seen_at)
 SELECT tenant_id, request_id, phase, ingested_at FROM jev_analytics.contents;
