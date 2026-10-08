CREATE TABLE jev_analytics.ingestion_status (
 tenant_id TEXT NOT NULL,
 source_id TEXT NOT NULL,
 state TEXT NOT NULL CHECK (state IN ('ok','error')),
 last_error TEXT,
 last_success_at TIMESTAMPTZ,
 observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 poll_interval_ms BIGINT NOT NULL CHECK (poll_interval_ms >= 100),
 rejected_lines BIGINT NOT NULL DEFAULT 0,
 rejection_counts JSONB NOT NULL DEFAULT '{}',
 PRIMARY KEY (tenant_id,source_id)
);
CREATE INDEX ingestion_status_tenant_observed_idx ON jev_analytics.ingestion_status (tenant_id, observed_at DESC);
