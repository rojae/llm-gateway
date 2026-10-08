-- The request identity row also serializes late content against payload purge.
ALTER TABLE jev_analytics.request_identities ADD COLUMN purged_at TIMESTAMPTZ;
