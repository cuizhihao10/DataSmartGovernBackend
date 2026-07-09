-- 本地 MySQL 兼容环境的等价迁移：用户侧数据源/同步任务默认按 id 倒序展示，审批状态不再作为普通任务列表字段。

UPDATE data_sync_task
SET approval_state = 'NOT_REQUIRED'
WHERE approval_state <> 'NOT_REQUIRED';

SET @drop_data_sync_task_approval_index = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'data_sync_task'
              AND index_name = 'idx_data_sync_task_approval'
        ),
        'ALTER TABLE data_sync_task DROP INDEX idx_data_sync_task_approval',
        'SELECT 1'
    )
);
PREPARE stmt FROM @drop_data_sync_task_approval_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_data_sync_task_project_id_desc = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'data_sync_task'
              AND index_name = 'idx_data_sync_task_project_id_desc'
        ),
        'ALTER TABLE data_sync_task ADD INDEX idx_data_sync_task_project_id_desc (tenant_id, project_id, id)',
        'SELECT 1'
    )
);
PREPARE stmt FROM @add_data_sync_task_project_id_desc;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_data_sync_task_group_id_desc = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'data_sync_task'
              AND index_name = 'idx_data_sync_task_group_id_desc'
        ),
        'ALTER TABLE data_sync_task ADD INDEX idx_data_sync_task_group_id_desc (tenant_id, project_id, group_code, id)',
        'SELECT 1'
    )
);
PREPARE stmt FROM @add_data_sync_task_group_id_desc;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE sync_task
SET approval_state = 'NOT_REQUIRED'
WHERE approval_state <> 'NOT_REQUIRED';

SET @drop_sync_task_approval_index = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'sync_task'
              AND index_name = 'idx_sync_task_approval'
        ),
        'ALTER TABLE sync_task DROP INDEX idx_sync_task_approval',
        'SELECT 1'
    )
);
PREPARE stmt FROM @drop_sync_task_approval_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_sync_task_project_id_desc = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'sync_task'
              AND index_name = 'idx_sync_task_project_id_desc'
        ),
        'ALTER TABLE sync_task ADD INDEX idx_sync_task_project_id_desc (tenant_id, project_id, id)',
        'SELECT 1'
    )
);
PREPARE stmt FROM @add_sync_task_project_id_desc;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_datasource_project_id_desc = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'datasource_config'
              AND index_name = 'idx_datasource_project_id_desc'
        ),
        'ALTER TABLE datasource_config ADD INDEX idx_datasource_project_id_desc (tenant_id, project_id, id)',
        'SELECT 1'
    )
);
PREPARE stmt FROM @add_datasource_project_id_desc;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_task_project_id_desc = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'task'
              AND index_name = 'idx_task_project_id_desc'
        ),
        'ALTER TABLE task ADD INDEX idx_task_project_id_desc (tenant_id, project_id, id)',
        'SELECT 1'
    )
);
PREPARE stmt FROM @add_task_project_id_desc;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_task_project_status_id_desc = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'task'
              AND index_name = 'idx_task_project_status_id_desc'
        ),
        'ALTER TABLE task ADD INDEX idx_task_project_status_id_desc (tenant_id, project_id, status, id)',
        'SELECT 1'
    )
);
PREPARE stmt FROM @add_task_project_status_id_desc;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_task_draft_project_id_desc = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'task_draft'
              AND index_name = 'idx_task_draft_project_id_desc'
        ),
        'ALTER TABLE task_draft ADD INDEX idx_task_draft_project_id_desc (tenant_id, project_id, id)',
        'SELECT 1'
    )
);
PREPARE stmt FROM @add_task_draft_project_id_desc;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_quality_rule_project_id_desc = (
    SELECT IF(
        NOT EXISTS (
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'quality_rule'
              AND index_name = 'idx_quality_rule_project_id_desc'
        ),
        'ALTER TABLE quality_rule ADD INDEX idx_quality_rule_project_id_desc (tenant_id, project_id, id)',
        'SELECT 1'
    )
);
PREPARE stmt FROM @add_quality_rule_project_id_desc;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
