-- DataSource names should be reusable after logical deletion.
--
-- Earlier versions used a table-level unique constraint on (tenant_id, project_id, name).
-- That constraint also covered rows whose status had already become DELETED, so users could
-- not recreate a datasource with the same visible name even though the old row no longer
-- appeared in the normal management list. The service layer has always treated DELETED rows
-- as out of the active namespace, so this migration aligns PostgreSQL with that product rule.
ALTER TABLE datasource_config
    DROP CONSTRAINT IF EXISTS uk_datasource_tenant_project_name;

CREATE UNIQUE INDEX IF NOT EXISTS uk_datasource_tenant_project_name_active
    ON datasource_config (tenant_id, project_id, name)
    WHERE status <> 'DELETED';

COMMENT ON INDEX uk_datasource_tenant_project_name_active IS
    '租户和项目内未删除数据源名称唯一；逻辑删除后的历史记录不阻塞同名数据源重新登记。';
