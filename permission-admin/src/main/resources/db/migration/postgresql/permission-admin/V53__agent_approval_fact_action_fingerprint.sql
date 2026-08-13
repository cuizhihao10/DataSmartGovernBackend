-- Persist the permission-admin generated binding for the exact low-sensitive
-- controlled action. NULL keeps historical rows readable, but evaluation treats
-- a NULL value as non-authorizing until a trusted registration creates a hash.
SET search_path TO permission_admin, public;

ALTER TABLE agent_tool_action_approval_fact
    ADD COLUMN IF NOT EXISTS action_fingerprint VARCHAR(256);

COMMENT ON COLUMN agent_tool_action_approval_fact.action_fingerprint IS
    'Server-calculated immutable low-sensitive fingerprint of the exact controlled action; NULL denotes a legacy history row that cannot authorize a controlled action.';
