-- Remove the retired standalone sync-template product model from legacy MySQL control databases.
-- Active FlashSync services use PostgreSQL and task-owned definitions. MySQL is retained only as
-- a connector/test source, so legacy control-plane tables must not continue advertising old APIs.

SET @schema_name = DATABASE();

DELETE FROM permission_route_policy
WHERE path_pattern LIKE '/api/sync/sync-templates%'
   OR resource_type = 'SYNC_TEMPLATE';

DELETE FROM permission_data_scope_policy
WHERE resource_type = 'SYNC_TEMPLATE';

UPDATE permission_menu
SET description = '数据同步任务、执行记录、人工介入和事故处理入口。',
    update_time = CURRENT_TIMESTAMP
WHERE menu_code = 'data-sync';

DELETE FROM sync_permission_policy_binding
WHERE binding_value = 'sync:template-center';

SET @sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'data_sync_task'
          AND column_name = 'template_id'
    ),
    'ALTER TABLE data_sync_task DROP COLUMN template_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'data_sync_object_execution'
          AND column_name = 'template_id'
    ),
    'ALTER TABLE data_sync_object_execution DROP COLUMN template_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'data_sync_audit_record'
          AND column_name = 'template_id'
    ),
    'ALTER TABLE data_sync_audit_record DROP COLUMN template_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'task_data_sync_worker_command_outbox'
          AND column_name = 'template_id'
    ),
    'ALTER TABLE task_data_sync_worker_command_outbox DROP COLUMN template_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'task_data_sync_worker_command_outbox'
          AND column_name = 'sync_template_id'
    ),
    'ALTER TABLE task_data_sync_worker_command_outbox DROP COLUMN sync_template_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

DROP TABLE IF EXISTS data_sync_template;
DROP TABLE IF EXISTS sync_checkpoint;
DROP TABLE IF EXISTS sync_execution;
DROP TABLE IF EXISTS sync_agent_command_receipt;
DROP TABLE IF EXISTS sync_task;
DROP TABLE IF EXISTS sync_template;
