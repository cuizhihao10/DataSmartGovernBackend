-- @Author : Cui
-- @Date: 2026/05/09 20:36
-- @Description DataSmart Govern Backend - data-sync 项目/工作空间范围字段迁移
-- @Version:1.0.0

-- ---------------------------------------------------------------------------
-- data-sync 项目/工作空间范围字段迁移
-- ---------------------------------------------------------------------------
-- 设计说明：
-- 1. 3.01 已经把 permission-admin 的数据范围结果下沉到 data-sync 查询链路；
-- 2. 但如果 data-sync 表没有 project_id/workspace_id，PROJECT 范围只能临时降级为租户范围；
-- 3. 本迁移为模板、任务、执行、checkpoint、错误样本、事故、审计补项目/空间字段；
-- 4. 对运行事实表冗余 project_id/workspace_id，是为了项目级看板、项目级 SLA、项目级成本和故障复盘不必频繁 join 任务表；
-- 5. 迁移使用 MySQL 8 的 information_schema + 动态 SQL，重复执行不会重复加列或重复建索引。

SET @schema_name = DATABASE();

-- data_sync_template
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN project_id BIGINT COMMENT ''项目 ID，用于租户内部二级隔离、项目级权限、项目级同步看板、项目级配额和成本审计'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，用于多团队协作空间、空间级筛选和后续空间级配额'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- data_sync_task
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_task' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_task ADD COLUMN project_id BIGINT COMMENT ''项目 ID，通常继承自同步模板，用于项目负责人可见性、项目级配额、项目级 SLA 和成本统计'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_task' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_task ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，通常继承自同步模板，用于空间级运营台筛选和多团队协作'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- data_sync_execution
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_execution' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_execution ADD COLUMN project_id BIGINT COMMENT ''项目 ID，冗余自同步任务，用于项目级执行历史、失败率、成本和容量分析'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_execution' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_execution ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，冗余自同步任务，用于空间级运行证据筛选'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- data_sync_checkpoint
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_checkpoint' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_checkpoint ADD COLUMN project_id BIGINT COMMENT ''项目 ID，冗余自同步任务，用于项目级断点恢复视图和数据保留策略'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_checkpoint' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_checkpoint ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，冗余自同步任务，用于空间级运行证据筛选'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- data_sync_error_sample
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_error_sample' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_error_sample ADD COLUMN project_id BIGINT COMMENT ''项目 ID，冗余自同步任务，用于项目级错误样本筛选和质量复盘'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_error_sample' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_error_sample ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，冗余自同步任务，用于空间级错误治理看板'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- data_sync_incident_record
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_incident_record' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_incident_record ADD COLUMN project_id BIGINT COMMENT ''项目 ID，用于项目级事故工作台、SLA 统计和项目负责人可见性控制'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_incident_record' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_incident_record ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，用于空间级事故筛选和多团队运营协作'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- data_sync_audit_record
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_audit_record' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_audit_record ADD COLUMN project_id BIGINT COMMENT ''项目 ID，可为空；用于未来项目级审计报表和权限证据查询'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_audit_record' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE data_sync_audit_record ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，可为空；用于未来空间级审计筛选'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填历史数据：任务继承模板项目/空间，运行事实继承任务项目/空间。
UPDATE data_sync_task task
JOIN data_sync_template template ON template.id = task.template_id
SET task.project_id = COALESCE(task.project_id, template.project_id),
    task.workspace_id = COALESCE(task.workspace_id, template.workspace_id);

UPDATE data_sync_execution execution
JOIN data_sync_task task ON task.id = execution.sync_task_id
SET execution.project_id = COALESCE(execution.project_id, task.project_id),
    execution.workspace_id = COALESCE(execution.workspace_id, task.workspace_id);

UPDATE data_sync_checkpoint checkpoint
JOIN data_sync_task task ON task.id = checkpoint.sync_task_id
SET checkpoint.project_id = COALESCE(checkpoint.project_id, task.project_id),
    checkpoint.workspace_id = COALESCE(checkpoint.workspace_id, task.workspace_id);

UPDATE data_sync_error_sample sample
JOIN data_sync_task task ON task.id = sample.sync_task_id
SET sample.project_id = COALESCE(sample.project_id, task.project_id),
    sample.workspace_id = COALESCE(sample.workspace_id, task.workspace_id);

UPDATE data_sync_incident_record incident
JOIN data_sync_task task ON task.id = incident.sync_task_id
SET incident.project_id = COALESCE(incident.project_id, task.project_id),
    incident.workspace_id = COALESCE(incident.workspace_id, task.workspace_id);

-- 索引幂等创建。
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND index_name = 'idx_data_sync_template_project_mode'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD INDEX idx_data_sync_template_project_mode (tenant_id, project_id, sync_mode, enabled)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'data_sync_task' AND index_name = 'idx_data_sync_task_project_state'),
    'SELECT 1',
    'ALTER TABLE data_sync_task ADD INDEX idx_data_sync_task_project_state (tenant_id, project_id, current_state, update_time)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'data_sync_execution' AND index_name = 'idx_data_sync_execution_project_state'),
    'SELECT 1',
    'ALTER TABLE data_sync_execution ADD INDEX idx_data_sync_execution_project_state (tenant_id, project_id, execution_state, create_time)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'data_sync_incident_record' AND index_name = 'idx_data_sync_incident_project_status'),
    'SELECT 1',
    'ALTER TABLE data_sync_incident_record ADD INDEX idx_data_sync_incident_project_status (tenant_id, project_id, incident_status, severity, create_time)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
