-- Response metadata and protected content follow the question retention/tombstone model.
ALTER TABLE monitoring_sessions ADD CONSTRAINT monitoring_sessions_response_scope UNIQUE(tenant_id,id,account_id,source);
ALTER TABLE monitoring_questions ADD CONSTRAINT monitoring_questions_response_scope UNIQUE(tenant_id,id,session_id,source);
CREATE TABLE monitoring_responses (
 tenant_id TEXT NOT NULL, id UUID NOT NULL, account_id UUID NOT NULL,
 session_id UUID, question_id UUID, source TEXT NOT NULL CHECK(source IN ('claude_code','codex')),
 emitted_at TIMESTAMPTZ NOT NULL, sequence BIGINT, model TEXT, attribution TEXT NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT now(), content_purged_at TIMESTAMPTZ, purged_at TIMESTAMPTZ,
 PRIMARY KEY(tenant_id,id),
 CHECK(question_id IS NULL OR session_id IS NOT NULL),
 FOREIGN KEY(tenant_id,account_id) REFERENCES monitoring_accounts(tenant_id,id),
 FOREIGN KEY(tenant_id,session_id,account_id,source) REFERENCES monitoring_sessions(tenant_id,id,account_id,source),
 FOREIGN KEY(tenant_id,question_id,session_id,source) REFERENCES monitoring_questions(tenant_id,id,session_id,source)
);
CREATE TABLE monitoring_response_contents (
 tenant_id TEXT NOT NULL, response_id UUID NOT NULL, text TEXT NOT NULL, truncated BOOLEAN NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,response_id),
 FOREIGN KEY(tenant_id,response_id) REFERENCES monitoring_responses(tenant_id,id)
);
CREATE INDEX monitoring_responses_session_idx ON monitoring_responses(tenant_id,session_id,emitted_at,sequence,id);
CREATE INDEX monitoring_responses_question_idx ON monitoring_responses(tenant_id,question_id);
CREATE INDEX monitoring_responses_received_idx ON monitoring_responses(tenant_id,received_at);
CREATE INDEX monitoring_response_contents_received_idx ON monitoring_response_contents(tenant_id,received_at);
