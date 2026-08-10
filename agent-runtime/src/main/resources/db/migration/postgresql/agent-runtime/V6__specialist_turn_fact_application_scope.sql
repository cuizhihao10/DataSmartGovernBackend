-- Bind durable specialist turn facts to the platform application layer as well
-- as the existing tenant and project layers.  V5 is intentionally left intact:
-- changing an applied Flyway migration would make existing installations fail
-- checksum validation instead of receiving a safe forward-only upgrade.
SET search_path TO agent_runtime, public;

ALTER TABLE agent_specialist_turn_fact
    ADD COLUMN IF NOT EXISTS application_id BIGINT;

-- A shared deployment normally already has permission_admin.permission_project.
-- The conditional form keeps agent-runtime independently bootstrapable: if the
-- permission schema is not available yet, legacy rows remain unreadable rather
-- than being assigned a guessed application.  The Java query contract always
-- requires application_id, so a legacy null value is fail-closed until a later
-- migration or controlled repair can resolve it from authoritative project data.
DO $$
BEGIN
    IF to_regclass('permission_admin.permission_project') IS NOT NULL THEN
        UPDATE agent_runtime.agent_specialist_turn_fact AS fact
        SET application_id = project.application_id
        FROM permission_admin.permission_project AS project
        WHERE fact.application_id IS NULL
          AND fact.tenant_id = project.tenant_id
          AND fact.project_id = project.project_id
          AND project.application_id IS NOT NULL
          AND project.application_id > 0;
    END IF;
END $$;

-- NOT VALID preserves availability for pre-V6 historical rows that cannot be
-- authoritatively backfilled yet, while PostgreSQL still enforces this exact
-- constraint for every new INSERT/UPDATE after the migration.  This prevents a
-- direct JDBC writer from creating another unbound fact outside the Java domain
-- validation path.
ALTER TABLE agent_specialist_turn_fact
    ADD CONSTRAINT ck_agent_specialist_turn_fact_application_scope
    CHECK (application_id IS NOT NULL AND application_id > 0) NOT VALID;

-- All application-aware reads use this index prefix before session/run and
-- update-time ordering.  It keeps the new isolation dimension inexpensive even
-- when a project audit contains a long specialist-turn history.
CREATE INDEX IF NOT EXISTS idx_agent_specialist_turn_fact_application_session
    ON agent_specialist_turn_fact (tenant_id, application_id, project_id, session_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_specialist_turn_fact_application_run
    ON agent_specialist_turn_fact (tenant_id, application_id, project_id, run_id, updated_at DESC);

COMMENT ON COLUMN agent_specialist_turn_fact.application_id IS
    'Authoritative application boundary for specialist Agent turn facts; legacy unbound rows are fail-closed and new rows require a positive value.';
