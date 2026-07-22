-- Immutable task-import artifacts support Agent dry-run, repair proposals and confirmed commit.
-- The current bounded implementation stores files up to the service limit in PostgreSQL BYTEA.
-- Object storage can replace the body column later without changing the public artifact reference.
CREATE TABLE IF NOT EXISTS sync_task_import_artifact (
    id BIGSERIAL PRIMARY KEY,
    artifact_ref VARCHAR(96) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    parent_artifact_id BIGINT NULL REFERENCES sync_task_import_artifact(id),
    version_number INTEGER NOT NULL DEFAULT 1,
    file_name VARCHAR(255) NOT NULL,
    file_format VARCHAR(16) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content_body BYTEA NOT NULL,
    content_size_bytes BIGINT NOT NULL,
    artifact_state VARCHAR(32) NOT NULL,
    dry_run_status VARCHAR(64),
    dry_run_digest VARCHAR(64),
    diagnostics_json TEXT,
    repair_patch_digest VARCHAR(64),
    create_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    CONSTRAINT ck_sync_task_import_artifact_version CHECK (version_number > 0),
    CONSTRAINT ck_sync_task_import_artifact_size CHECK (content_size_bytes > 0)
);

CREATE INDEX IF NOT EXISTS idx_sync_task_import_artifact_scope
    ON sync_task_import_artifact (tenant_id, project_id, owner_id, id DESC);

CREATE INDEX IF NOT EXISTS idx_sync_task_import_artifact_parent
    ON sync_task_import_artifact (parent_artifact_id, version_number DESC);

COMMENT ON TABLE sync_task_import_artifact IS
    'Immutable CSV/XLSX task-import artifacts. Models only receive artifact refs and structured diagnostics; repaired content is a new version.';
