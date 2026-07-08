SET search_path TO datasource_management, public;

ALTER TABLE sync_task
    DROP CONSTRAINT IF EXISTS uk_sync_task_tenant_project_name;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_task_tenant_project_name_active
    ON sync_task (tenant_id, project_id, name)
    WHERE current_state <> 'ARCHIVED';
