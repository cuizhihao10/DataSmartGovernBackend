-- Backfill ownership for datasource rows created before owner_id/created_by became mandatory.
SET search_path TO datasource_management, public;

DO $$
BEGIN
    IF to_regclass('permission_admin.permission_project') IS NOT NULL THEN
        EXECUTE $sql$
            UPDATE datasource_management.datasource_config datasource
            SET owner_id = project.owner_actor_id,
                created_by = CASE
                    WHEN datasource.created_by IS NULL OR datasource.created_by = 0
                        THEN project.owner_actor_id
                    ELSE datasource.created_by
                END,
                update_time = CURRENT_TIMESTAMP
            FROM permission_admin.permission_project project
            WHERE datasource.tenant_id = project.tenant_id
              AND datasource.project_id = project.project_id
              AND project.owner_actor_id IS NOT NULL
              AND project.owner_actor_id > 0
              AND (datasource.owner_id IS NULL OR datasource.owner_id = 0)
        $sql$;
    END IF;
END $$;

COMMENT ON COLUMN datasource_config.owner_id IS
    'Current datasource owner actor id. Project membership alone does not expose another owner datasource; explicit instance ACL or project OWNER/MANAGER administration is required.';

