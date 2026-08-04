CREATE TABLE IF NOT EXISTS agent_tool_action_approval_fact (
    id BIGSERIAL PRIMARY KEY,
    approval_fact_id VARCHAR(160) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(96) NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    command_id VARCHAR(160) NOT NULL,
    tool_code VARCHAR(160) NOT NULL,
    policy_version VARCHAR(160),
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP,
    approved_by_actor_id VARCHAR(128),
    reason_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_approval_fact_scope
    ON agent_tool_action_approval_fact
    (tenant_id, project_id, actor_id, session_id, run_id, command_id, tool_code, status);
CREATE INDEX IF NOT EXISTS idx_agent_approval_fact_expiry
    ON agent_tool_action_approval_fact (status, expires_at);

INSERT INTO permission_route_policy
(tenant_id, policy_name, role_code, http_method, path_pattern, resource_type, action, effect, priority, enabled,
 description, create_time, update_time)
VALUES
(0, 'Trusted service registers Agent approval facts', 'SERVICE_ACCOUNT', 'POST',
 '/api/permission/agent/tool-action-approvals/facts', 'AGENT_TOOL_APPROVAL_FACT', 'REGISTER', 'ALLOW', 1100, TRUE,
 'Gateway role policy is only the first gate; permission-admin also requires an allowlisted source service and shared internal credential.',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
