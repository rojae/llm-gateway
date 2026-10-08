CREATE SCHEMA IF NOT EXISTS jev_analytics;
CREATE TABLE IF NOT EXISTS jev_analytics.requests (
 request_id TEXT PRIMARY KEY, started_at TIMESTAMPTZ NOT NULL,
 provider TEXT, model TEXT, key_id TEXT, session_id TEXT,
 traffic TEXT NOT NULL, outcome TEXT NOT NULL, status INTEGER, usage_availability TEXT NOT NULL,
 prompt_tokens BIGINT, completion_tokens BIGINT, cache_write_tokens BIGINT, cache_write_1h_tokens BIGINT,
 cache_read_tokens BIGINT, total_tokens BIGINT, cost_usd NUMERIC(24,12),
 total_ms DOUBLE PRECISION, first_byte_ms DOUBLE PRECISION, record JSONB NOT NULL,
 ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS requests_started_idx ON jev_analytics.requests (started_at DESC, request_id DESC);
CREATE INDEX IF NOT EXISTS requests_traffic_started_idx ON jev_analytics.requests (traffic, started_at DESC);
CREATE TABLE IF NOT EXISTS jev_analytics.contents (
 request_id TEXT NOT NULL, phase TEXT NOT NULL CHECK (phase IN ('request','response')),
 record JSONB NOT NULL, ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (request_id,phase)
);
CREATE TABLE IF NOT EXISTS jev_analytics.checkpoints (
 source_id TEXT NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('access','content')),
 file_id TEXT NOT NULL, file_name TEXT NOT NULL, offset_bytes BIGINT NOT NULL DEFAULT 0,
 rejected_lines BIGINT NOT NULL DEFAULT 0, rejection_counts JSONB NOT NULL DEFAULT '{}',
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (source_id,kind,file_id)
);
ALTER TABLE jev_analytics.checkpoints ADD COLUMN IF NOT EXISTS anchor_hash TEXT;
ALTER TABLE jev_analytics.checkpoints ADD COLUMN IF NOT EXISTS observed_version TEXT;
ALTER TABLE jev_analytics.checkpoints ADD COLUMN IF NOT EXISTS completed BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE jev_analytics.requests ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'gateway' CHECK (source IN ('gateway','claude_code'));
ALTER TABLE jev_analytics.requests ADD COLUMN IF NOT EXISTS billing_mode TEXT NOT NULL DEFAULT 'api_key' CHECK (billing_mode IN ('api_key','subscription_declared','api_key_declared','unknown'));
ALTER TABLE jev_analytics.requests ADD COLUMN IF NOT EXISTS api_equivalent_cost_usd NUMERIC(24,12);
CREATE INDEX IF NOT EXISTS requests_source_started_idx ON jev_analytics.requests (source,started_at DESC);
