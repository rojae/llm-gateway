-- Unqualified names use Flyway's current schema (including already renamed installations).
CREATE TABLE monitoring_accounts (
 tenant_id TEXT NOT NULL, id UUID NOT NULL, name TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_by TEXT NOT NULL,
 PRIMARY KEY (tenant_id,id)
);
CREATE TABLE monitoring_registrations (
 tenant_id TEXT NOT NULL, id UUID NOT NULL, account_id UUID NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,id,account_id),
 FOREIGN KEY(tenant_id,account_id) REFERENCES monitoring_accounts(tenant_id,id)
);
ALTER TABLE collector_credentials ADD COLUMN owner_account_id UUID;
ALTER TABLE collector_credentials ADD COLUMN registration_id UUID;
ALTER TABLE collector_credentials ADD CONSTRAINT collector_owner_fk
 FOREIGN KEY (tenant_id,owner_account_id) REFERENCES monitoring_accounts(tenant_id,id);
ALTER TABLE collector_credentials ADD CONSTRAINT collector_registration_fk
 FOREIGN KEY(tenant_id,registration_id,owner_account_id) REFERENCES monitoring_registrations(tenant_id,id,account_id);
ALTER TABLE collector_credentials ADD CONSTRAINT collector_registration_owner
 CHECK (owner_account_id IS NULL OR registration_id IS NOT NULL);
CREATE TABLE monitoring_sessions (
 tenant_id TEXT NOT NULL, id UUID NOT NULL, account_id UUID NOT NULL,
 source TEXT NOT NULL CHECK (source IN ('claude_code','codex')), external_session_id TEXT NOT NULL,
 provider_account_hash CHAR(64), provider_account_status TEXT NOT NULL DEFAULT 'unknown',
 cli_version TEXT, first_seen_at TIMESTAMPTZ NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL,
 purged_at TIMESTAMPTZ,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,account_id,source,external_session_id),
 FOREIGN KEY(tenant_id,account_id) REFERENCES monitoring_accounts(tenant_id,id)
);
CREATE TABLE monitoring_questions (
 tenant_id TEXT NOT NULL, id UUID NOT NULL, session_id UUID NOT NULL,
 external_prompt_id TEXT, question_identity TEXT NOT NULL, started_at TIMESTAMPTZ NOT NULL,
 source TEXT NOT NULL, model TEXT, received_at TIMESTAMPTZ NOT NULL DEFAULT now(), purged_at TIMESTAMPTZ, content_purged_at TIMESTAMPTZ,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,session_id,question_identity),
 FOREIGN KEY(tenant_id,session_id) REFERENCES monitoring_sessions(tenant_id,id)
);
CREATE TABLE monitoring_question_contents (
 tenant_id TEXT NOT NULL, question_id UUID NOT NULL, text TEXT NOT NULL, truncated BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,question_id),
 FOREIGN KEY(tenant_id,question_id) REFERENCES monitoring_questions(tenant_id,id)
);
-- Event identities and purged questions/sessions remain as tombstones after payload retention.
CREATE TABLE monitoring_event_identities (
 tenant_id TEXT NOT NULL, account_id UUID NOT NULL, source TEXT NOT NULL, identity TEXT NOT NULL,
 first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,account_id,source,identity)
);
CREATE TABLE monitoring_events (
 tenant_id TEXT NOT NULL, id UUID NOT NULL, session_id UUID, question_id UUID,
 account_id UUID NOT NULL, source TEXT NOT NULL, event_kind TEXT NOT NULL,
 emitted_at TIMESTAMPTZ NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 cli_version TEXT, adapter_version TEXT NOT NULL DEFAULT 'native-json-v1',
 request_identity TEXT, external_prompt_id TEXT, model TEXT,
 input_tokens BIGINT, output_tokens BIGINT, cache_read_tokens BIGINT, cache_write_tokens BIGINT,
 total_tokens BIGINT, api_equivalent_cost_usd NUMERIC(30,12),
 scope TEXT NOT NULL, semantics TEXT NOT NULL, attribution TEXT NOT NULL,
 usage_availability TEXT NOT NULL, supported BOOLEAN NOT NULL,
 PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,account_id) REFERENCES monitoring_accounts(tenant_id,id),
 FOREIGN KEY(tenant_id,session_id) REFERENCES monitoring_sessions(tenant_id,id),
 FOREIGN KEY(tenant_id,question_id) REFERENCES monitoring_questions(tenant_id,id)
);
CREATE INDEX monitoring_sessions_owner_time_idx ON monitoring_sessions(tenant_id,account_id,last_seen_at DESC);
CREATE INDEX monitoring_questions_received_idx ON monitoring_questions(tenant_id,received_at);
CREATE INDEX monitoring_questions_session_time_idx ON monitoring_questions(tenant_id,session_id,started_at,id);
CREATE INDEX monitoring_events_session_idx ON monitoring_events(tenant_id,session_id,emitted_at,id);
CREATE INDEX monitoring_events_question_idx ON monitoring_events(tenant_id,question_id);
CREATE TABLE monitoring_status (
 tenant_id TEXT PRIMARY KEY, last_received_at TIMESTAMPTZ,
 stored BIGINT NOT NULL DEFAULT 0, duplicates BIGINT NOT NULL DEFAULT 0,
 rejected BIGINT NOT NULL DEFAULT 0, unsupported BIGINT NOT NULL DEFAULT 0, unattributed BIGINT NOT NULL DEFAULT 0
);
