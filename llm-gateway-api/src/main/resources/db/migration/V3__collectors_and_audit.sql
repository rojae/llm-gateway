CREATE TABLE jev_analytics.collector_credentials (
 id UUID PRIMARY KEY,
 tenant_id TEXT NOT NULL,
 name TEXT NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE,
 prefix TEXT NOT NULL,
 billing_mode TEXT NOT NULL CHECK (billing_mode IN ('subscription_declared','api_key_declared','unknown')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 created_by TEXT NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 last_used_at TIMESTAMPTZ
);
CREATE INDEX collector_credentials_tenant_created_idx ON jev_analytics.collector_credentials (tenant_id, created_at DESC);
CREATE TABLE jev_analytics.audit_events (
 id UUID PRIMARY KEY,
 time TIMESTAMPTZ NOT NULL DEFAULT now(),
 tenant_id TEXT NOT NULL,
 actor TEXT NOT NULL,
 action TEXT NOT NULL,
 target TEXT NOT NULL,
 outcome TEXT NOT NULL
);
CREATE INDEX audit_events_tenant_time_idx ON jev_analytics.audit_events (tenant_id, time DESC, id DESC);
CREATE FUNCTION jev_analytics.audit_events_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'audit_events_append_only';
END;
$$;
CREATE TRIGGER audit_events_immutable BEFORE UPDATE OR DELETE ON jev_analytics.audit_events
 FOR EACH ROW EXECUTE FUNCTION jev_analytics.audit_events_immutable();
