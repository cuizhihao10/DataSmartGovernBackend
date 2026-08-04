CREATE TABLE IF NOT EXISTS agent_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(96) NOT NULL UNIQUE,
    agent_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    workspace_id BIGINT,
    actor_id VARCHAR(128) NOT NULL,
    actor_role VARCHAR(64),
    actor_type VARCHAR(64),
    authorized_project_roles TEXT,
    channel VARCHAR(64) NOT NULL,
    objective TEXT NOT NULL,
    isolation_level VARCHAR(32) NOT NULL,
    workspace_key VARCHAR(512) NOT NULL,
    state VARCHAR(32) NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at TIMESTAMP,
    last_message_at TIMESTAMP NOT NULL,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_session_owner_history
    ON agent_session (tenant_id, project_id, actor_id, pinned DESC, update_time DESC);
CREATE INDEX IF NOT EXISTS idx_agent_session_archive
    ON agent_session (tenant_id, project_id, actor_id, archived_at, update_time DESC);

CREATE TABLE IF NOT EXISTS agent_delegation (
    id BIGSERIAL PRIMARY KEY,
    delegation_id VARCHAR(96) NOT NULL UNIQUE,
    session_id VARCHAR(96) NOT NULL UNIQUE REFERENCES agent_session(session_id) ON DELETE CASCADE,
    agent_id VARCHAR(128) NOT NULL,
    user_actor_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    tool_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    resource_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    update_time TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_delegation_scope
    ON agent_delegation (tenant_id, project_id, user_actor_id, status, expires_at);

CREATE TABLE IF NOT EXISTS agent_session_tool_binding (
    id BIGSERIAL PRIMARY KEY,
    binding_id VARCHAR(96) NOT NULL UNIQUE,
    session_id VARCHAR(96) NOT NULL REFERENCES agent_session(session_id) ON DELETE CASCADE,
    tool_code VARCHAR(160) NOT NULL,
    tool_type VARCHAR(64) NOT NULL,
    display_name VARCHAR(255),
    target_service VARCHAR(160),
    target_endpoint VARCHAR(512),
    target_resource_id BIGINT,
    read_only BOOLEAN NOT NULL,
    risk_level VARCHAR(64),
    execution_mode VARCHAR(64),
    requires_approval BOOLEAN NOT NULL,
    idempotent BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    allowed_actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    create_time TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_tool_binding_session ON agent_session_tool_binding (session_id, create_time);

CREATE TABLE IF NOT EXISTS agent_run (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(96) NOT NULL UNIQUE,
    session_id VARCHAR(96) NOT NULL REFERENCES agent_session(session_id) ON DELETE CASCADE,
    state VARCHAR(32) NOT NULL,
    workload_type VARCHAR(128),
    user_input_preview TEXT,
    dry_run BOOLEAN NOT NULL,
    require_human_approval BOOLEAN NOT NULL,
    next_actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    variables JSONB NOT NULL DEFAULT '{}'::jsonb,
    message TEXT,
    create_time TIMESTAMP NOT NULL,
    update_time TIMESTAMP NOT NULL,
    finish_time TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run (session_id, create_time DESC);

CREATE TABLE IF NOT EXISTS agent_conversation_message (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(96) NOT NULL UNIQUE,
    session_id VARCHAR(96) NOT NULL REFERENCES agent_session(session_id) ON DELETE CASCADE,
    run_id VARCHAR(96),
    role VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    create_time TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_message_session ON agent_conversation_message (session_id, create_time);

CREATE TABLE IF NOT EXISTS agent_tool_action_approval_confirmation (
    id BIGSERIAL PRIMARY KEY,
    confirmation_id VARCHAR(160) NOT NULL UNIQUE,
    proposal_id VARCHAR(160),
    client_request_id VARCHAR(160),
    payload_reference VARCHAR(512),
    run_id VARCHAR(96),
    payload_key VARCHAR(160),
    tenant_id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    confirming_actor_id VARCHAR(128),
    tool_name VARCHAR(160) NOT NULL,
    graph_id VARCHAR(160),
    contract_id VARCHAR(160),
    policy_version VARCHAR(160),
    payload_policy VARCHAR(160),
    payload_body_available BOOLEAN NOT NULL,
    payload_size_bytes INTEGER NOT NULL,
    payload_metadata_digest VARCHAR(256),
    accepted_payload_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    confirmed BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS idx_agent_approval_confirmation_scope
    ON agent_tool_action_approval_confirmation (tenant_id, project_id, run_id, tool_name, expires_at);
