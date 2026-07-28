-- Replace the obsolete reusable-template product model with a strict one-to-one task definition.
-- Existing tasks keep their configuration: each task receives an independent copy of the
-- definition previously referenced through data_sync_task.template_id.

CREATE TABLE data_sync_task_definition (
    task_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    project_id BIGINT,
    workspace_id BIGINT,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    source_datasource_id BIGINT NOT NULL,
    target_datasource_id BIGINT NOT NULL,
    source_schema_name VARCHAR(128),
    source_object_name VARCHAR(128),
    target_schema_name VARCHAR(128),
    target_object_name VARCHAR(128),
    source_connector_type VARCHAR(64),
    target_connector_type VARCHAR(64),
    sync_mode VARCHAR(64) NOT NULL,
    sync_scope_type VARCHAR(64) NOT NULL DEFAULT 'SINGLE_OBJECT',
    write_strategy VARCHAR(64) NOT NULL DEFAULT 'INSERT',
    primary_key_field VARCHAR(128),
    incremental_field VARCHAR(128),
    field_mapping_config TEXT,
    object_mapping_config TEXT,
    filter_config TEXT,
    custom_sql_config TEXT,
    partition_config TEXT,
    retry_policy TEXT,
    timeout_policy TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT,
    updated_by BIGINT,
    create_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    update_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_data_sync_task_definition_task
        FOREIGN KEY (task_id) REFERENCES data_sync_task (id) ON DELETE CASCADE,
    CONSTRAINT ck_data_sync_task_definition_tenant CHECK (tenant_id >= 0),
    CONSTRAINT ck_data_sync_task_definition_datasources
        CHECK (source_datasource_id > 0 AND target_datasource_id > 0)
);

INSERT INTO data_sync_task_definition (
    task_id, tenant_id, project_id, workspace_id, name, description,
    source_datasource_id, target_datasource_id,
    source_schema_name, source_object_name, target_schema_name, target_object_name,
    source_connector_type, target_connector_type, sync_mode, sync_scope_type,
    write_strategy, primary_key_field, incremental_field, field_mapping_config,
    object_mapping_config, filter_config, custom_sql_config, partition_config,
    retry_policy, timeout_policy, enabled, created_by, updated_by, create_time, update_time
)
SELECT
    task.id, task.tenant_id, task.project_id, task.workspace_id,
    task.name, COALESCE(task.description, definition.description),
    definition.source_datasource_id, definition.target_datasource_id,
    definition.source_schema_name, definition.source_object_name,
    definition.target_schema_name, definition.target_object_name,
    definition.source_connector_type, definition.target_connector_type,
    definition.sync_mode, definition.sync_scope_type, definition.write_strategy,
    definition.primary_key_field, definition.incremental_field,
    definition.field_mapping_config, definition.object_mapping_config,
    definition.filter_config, definition.custom_sql_config, definition.partition_config,
    definition.retry_policy, definition.timeout_policy, definition.enabled,
    COALESCE(task.owner_id, definition.created_by), definition.updated_by,
    LEAST(task.create_time, definition.create_time),
    GREATEST(task.update_time, definition.update_time)
FROM data_sync_task task
JOIN data_sync_template definition ON definition.id = task.template_id;

CREATE INDEX idx_data_sync_task_definition_project_mode
    ON data_sync_task_definition (tenant_id, project_id, sync_mode, enabled);
CREATE INDEX idx_data_sync_task_definition_source
    ON data_sync_task_definition (source_datasource_id);
CREATE INDEX idx_data_sync_task_definition_target
    ON data_sync_task_definition (target_datasource_id);

ALTER TABLE data_sync_task DROP CONSTRAINT IF EXISTS ck_data_sync_task_template_positive;
DROP INDEX IF EXISTS idx_data_sync_task_template;
ALTER TABLE data_sync_task DROP COLUMN template_id;

DROP INDEX IF EXISTS idx_data_sync_object_execution_template;
ALTER TABLE data_sync_object_execution DROP COLUMN IF EXISTS template_id;

DROP INDEX IF EXISTS idx_data_sync_audit_template_time;
ALTER TABLE data_sync_audit_record DROP COLUMN IF EXISTS template_id;

DROP TABLE data_sync_template;

COMMENT ON TABLE data_sync_task_definition IS
    '同步任务的一对一定义快照；task_id 同时是唯一标识，不能脱离任务独立创建、展示或复用';
COMMENT ON COLUMN data_sync_task_definition.task_id IS
    '关联 data_sync_task.id；所有配置查询、预检查和执行均通过 taskId 进入';
