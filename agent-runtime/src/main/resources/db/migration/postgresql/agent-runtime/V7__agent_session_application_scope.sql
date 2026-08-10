-- Keep the product application boundary alongside the durable Agent session.
-- Legacy rows remain nullable and are treated as compatibility records; new
-- Gateway-created sessions write the trusted application ID when available.
ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS application_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_agent_session_application_scope
    ON agent_session (tenant_id, application_id, project_id, actor_id, update_time DESC);

COMMENT ON COLUMN agent_session.application_id IS
    'Trusted product application boundary; never inferred from project_id';
