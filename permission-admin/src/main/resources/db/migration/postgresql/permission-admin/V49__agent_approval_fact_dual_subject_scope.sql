-- Extend durable Agent tool-action approval facts from a single user-centric
-- scope into the production audit shape: user subject + Agent subject +
-- tenant/application/project isolation. The migration is forward-only because
-- V46 may already have been applied in local and customer-like environments.
SET search_path TO permission_admin, public;

ALTER TABLE agent_tool_action_approval_fact
    ADD COLUMN IF NOT EXISTS application_id BIGINT,
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS agent_id VARCHAR(160),
    ADD COLUMN IF NOT EXISTS delegation_id VARCHAR(160);

-- user_id is the historical actor_id for rows created before the double-subject
-- contract existed. application_id can be reconstructed only when the project
-- directory is present and authoritative. agent_id/delegation_id cannot be
-- guessed safely, so old rows remain incomplete and will fail closed when a
-- caller attempts to reuse them for a new execution.
UPDATE agent_tool_action_approval_fact
SET user_id = actor_id
WHERE user_id IS NULL
  AND actor_id IS NOT NULL
  AND length(btrim(actor_id)) > 0;

DO $$
BEGIN
    IF to_regclass('permission_admin.permission_project') IS NOT NULL THEN
        UPDATE agent_tool_action_approval_fact AS fact
        SET application_id = project.application_id
        FROM permission_admin.permission_project AS project
        WHERE fact.application_id IS NULL
          AND fact.tenant_id = project.tenant_id
          AND fact.project_id = project.project_id
          AND project.application_id IS NOT NULL
          AND project.application_id > 0;
    END IF;
END $$;

ALTER TABLE agent_tool_action_approval_fact
    ADD CONSTRAINT ck_agent_approval_fact_dual_subject_scope
    CHECK (
        application_id IS NOT NULL
        AND application_id > 0
        AND length(btrim(user_id)) > 0
        AND length(btrim(actor_id)) > 0
        AND length(btrim(agent_id)) > 0
        AND length(btrim(session_id)) > 0
        AND length(btrim(run_id)) > 0
        AND length(btrim(delegation_id)) > 0
    ) NOT VALID;

CREATE INDEX IF NOT EXISTS idx_agent_approval_fact_dual_subject_scope
    ON agent_tool_action_approval_fact
    (tenant_id, application_id, project_id, user_id, actor_id, agent_id, delegation_id,
     session_id, run_id, command_id, tool_code, status);

COMMENT ON COLUMN agent_tool_action_approval_fact.application_id IS
    'Application isolation boundary for approval facts; new facts require a positive value.';
COMMENT ON COLUMN agent_tool_action_approval_fact.user_id IS
    'Human user represented by the Agent. It is stored separately from actor_id so future identity providers can distinguish account id from platform actor id.';
COMMENT ON COLUMN agent_tool_action_approval_fact.agent_id IS
    'Agent principal that will consume the approval fact.';
COMMENT ON COLUMN agent_tool_action_approval_fact.delegation_id IS
    'Delegation fact linking the human user to the Agent principal for this session/run.';
