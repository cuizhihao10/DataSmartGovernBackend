SET search_path TO datasource_management, public;

ALTER TABLE datasource_config
    ADD COLUMN IF NOT EXISTS owner_id BIGINT;

ALTER TABLE datasource_config
    ADD COLUMN IF NOT EXISTS created_by BIGINT;

UPDATE datasource_config
SET owner_id = COALESCE(owner_id, 0),
    created_by = COALESCE(created_by, owner_id, 0)
WHERE owner_id IS NULL
   OR created_by IS NULL;

ALTER TABLE datasource_config
    ALTER COLUMN owner_id SET DEFAULT 0,
    ALTER COLUMN owner_id SET NOT NULL,
    ALTER COLUMN created_by SET DEFAULT 0,
    ALTER COLUMN created_by SET NOT NULL;

COMMENT ON COLUMN datasource_config.owner_id IS
    'Datasource owner actor id. Instance MANAGE grants can edit/use but cannot delete; deletion is limited to owner or project manager.';
COMMENT ON COLUMN datasource_config.created_by IS
    'Datasource creator actor id. Normally equals owner_id; retained separately for future delegated creation or ownership transfer audit.';

CREATE INDEX IF NOT EXISTS idx_datasource_owner_project
    ON datasource_config (tenant_id, project_id, owner_id, status, id DESC);

CREATE INDEX IF NOT EXISTS idx_datasource_created_by
    ON datasource_config (tenant_id, created_by, id DESC);

COMMENT ON TABLE datasource_authorization IS
    'Datasource instance authorization ledger. An authorization becomes effective only when the actor is also a member of the datasource project; it does not grant delete permission.';
