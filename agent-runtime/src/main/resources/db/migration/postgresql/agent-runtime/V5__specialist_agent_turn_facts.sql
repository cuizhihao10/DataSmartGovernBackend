-- Durable low-sensitive facts emitted by specialist Agents.
-- This table deliberately stores references and summaries only; it is not a prompt,
-- tool-argument, SQL, credential, sample-data, or model-output archive.
SET search_path TO agent_runtime, public;

CREATE TABLE IF NOT EXISTS agent_specialist_turn_fact (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    run_id VARCHAR(160) NOT NULL,
    turn_id VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(320) NOT NULL,
    agent_id VARCHAR(160) NOT NULL,
    role VARCHAR(80) NOT NULL,
    delegation_id VARCHAR(256),
    status VARCHAR(40) NOT NULL,
    low_sensitive_summary VARCHAR(2048) NOT NULL DEFAULT '',
    model_invocation_id VARCHAR(256),
    model_name VARCHAR(256),
    tool_activity_summary_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    duration_millis BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- The idempotency key is globally unique so a reused key can never silently
    -- become a record belonging to a different tenant, project, actor, or Agent.
    CONSTRAINT uk_agent_specialist_turn_fact_idempotency UNIQUE (idempotency_key),
    -- One specialist turn has one immutable identity even if a retry generates
    -- an accidentally different idempotency key.
    CONSTRAINT uk_agent_specialist_turn_fact_turn UNIQUE (session_id, run_id, turn_id),
    CONSTRAINT ck_agent_specialist_turn_fact_identifiers CHECK (
        length(btrim(user_id)) > 0
        AND length(btrim(session_id)) > 0
        AND length(btrim(run_id)) > 0
        AND length(btrim(turn_id)) > 0
        AND length(btrim(idempotency_key)) > 0
        AND length(btrim(agent_id)) > 0
        AND length(btrim(role)) > 0
        AND length(btrim(status)) > 0
    ),
    CONSTRAINT ck_agent_specialist_turn_fact_scope CHECK (tenant_id > 0 AND project_id > 0),
    CONSTRAINT ck_agent_specialist_turn_fact_status CHECK (status ~ '^[A-Z][A-Z0-9_-]{0,39}$'),
    CONSTRAINT ck_agent_specialist_turn_fact_role CHECK (role ~ '^[A-Z][A-Z0-9_-]{0,79}$'),
    CONSTRAINT ck_agent_specialist_turn_fact_duration CHECK (duration_millis IS NULL OR duration_millis >= 0),
    CONSTRAINT ck_agent_specialist_turn_fact_time_order
        CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at),
    CONSTRAINT ck_agent_specialist_turn_fact_tool_refs_array
        CHECK (jsonb_typeof(tool_activity_summary_refs) = 'array'),
    CONSTRAINT ck_agent_specialist_turn_fact_evidence_refs_array
        CHECK (jsonb_typeof(evidence_refs) = 'array')
);

-- SELF queries bind actor_id after tenant and project. The order matches the
-- equality predicates and the updated-time ordering used by the JDBC store.
CREATE INDEX IF NOT EXISTS idx_agent_specialist_turn_fact_scope_session
    ON agent_specialist_turn_fact (tenant_id, project_id, user_id, session_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_specialist_turn_fact_scope_run
    ON agent_specialist_turn_fact (tenant_id, project_id, user_id, run_id, updated_at DESC);

-- PROJECT, TENANT, and PLATFORM audit reads intentionally omit user_id. These
-- companion indexes avoid turning an authorized project audit into a broad scan.
CREATE INDEX IF NOT EXISTS idx_agent_specialist_turn_fact_project_session
    ON agent_specialist_turn_fact (tenant_id, project_id, session_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_specialist_turn_fact_project_run
    ON agent_specialist_turn_fact (tenant_id, project_id, run_id, updated_at DESC);

-- This index supports specialist-oriented operational views without exposing
-- any sensitive payload. It is not used as an authorization boundary.
CREATE INDEX IF NOT EXISTS idx_agent_specialist_turn_fact_project_agent
    ON agent_specialist_turn_fact (tenant_id, project_id, agent_id, updated_at DESC);

COMMENT ON TABLE agent_specialist_turn_fact IS
    'Low-sensitive durable specialist Agent turn facts; never store prompt, reasoning, SQL, tool arguments, credentials, samples, or raw model output.';
COMMENT ON COLUMN agent_specialist_turn_fact.user_id IS
    'Business actor represented by the Agent; every read must re-check the trusted actor scope.';
COMMENT ON COLUMN agent_specialist_turn_fact.tenant_id IS
    'Tenant isolation boundary; must be a positive identifier.';
COMMENT ON COLUMN agent_specialist_turn_fact.project_id IS
    'Project isolation boundary; the retired workspace level is intentionally absent.';
COMMENT ON COLUMN agent_specialist_turn_fact.session_id IS
    'Durable Agent conversation locator, not an authorization credential.';
COMMENT ON COLUMN agent_specialist_turn_fact.run_id IS
    'One Agent execution run locator, not an authorization credential.';
COMMENT ON COLUMN agent_specialist_turn_fact.turn_id IS
    'Stable specialist turn identity within a session and run.';
COMMENT ON COLUMN agent_specialist_turn_fact.idempotency_key IS
    'Retry key bound to the complete immutable turn identity; a cross-subject reuse is rejected.';
COMMENT ON COLUMN agent_specialist_turn_fact.agent_id IS
    'Stable specialist Agent identifier, not a human user identity.';
COMMENT ON COLUMN agent_specialist_turn_fact.role IS
    'Normalized specialist role code such as KNOWLEDGE_AGENT or PRECHECK_AGENT.';
COMMENT ON COLUMN agent_specialist_turn_fact.delegation_id IS
    'Low-sensitive delegation locator; the delegation document and permissions are stored elsewhere.';
COMMENT ON COLUMN agent_specialist_turn_fact.status IS
    'Normalized public lifecycle status of the specialist turn.';
COMMENT ON COLUMN agent_specialist_turn_fact.low_sensitive_summary IS
    'Bounded human-readable summary only; prompt, reasoning, SQL, tool arguments, credentials, samples, and raw output are forbidden.';
COMMENT ON COLUMN agent_specialist_turn_fact.model_invocation_id IS
    'Provider invocation locator only; the request and response body are not stored here.';
COMMENT ON COLUMN agent_specialist_turn_fact.model_name IS
    'Actual model name used by the provider; model output is not stored here.';
COMMENT ON COLUMN agent_specialist_turn_fact.tool_activity_summary_refs IS
    'Array of low-sensitive activity locators; tool arguments and tool output正文 are not stored here.';
COMMENT ON COLUMN agent_specialist_turn_fact.evidence_refs IS
    'Array of RAG or metadata evidence locators; evidence正文 and samples are not stored here.';
COMMENT ON COLUMN agent_specialist_turn_fact.duration_millis IS
    'Elapsed specialist turn duration in milliseconds.';
COMMENT ON COLUMN agent_specialist_turn_fact.started_at IS
    'Absolute start time of the specialist turn.';
COMMENT ON COLUMN agent_specialist_turn_fact.finished_at IS
    'Absolute finish time; null is allowed while the turn is running.';
COMMENT ON COLUMN agent_specialist_turn_fact.created_at IS
    'First persistence time of the immutable turn identity.';
COMMENT ON COLUMN agent_specialist_turn_fact.updated_at IS
    'Latest durable update time for mutable lifecycle fields.';
