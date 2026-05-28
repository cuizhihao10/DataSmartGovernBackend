-- ---------------------------------------------------------------------------
-- 2026-05-10 datasource-management 项目/工作空间隔离字段补齐迁移
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. datasource-management 早期只按租户或全局名称管理数据源、同步模板和同步任务；
-- 2. permission-admin/gateway 已经开始透传 PROJECT 数据范围和授权项目集合；
-- 3. 如果 datasource-management 没有 project_id/workspace_id，就无法真正消费 PROJECT 范围；
-- 4. 本迁移为已有数据库补齐字段、索引和历史默认值，让数据源与旧同步控制面具备项目级隔离基础。
--
-- 设计约束：
-- - 默认把历史数据回填到 project_id=1，保证旧数据可继续查询；
-- - sync_task 的项目/空间从 sync_template 继承，避免任务与模板归属不一致；
-- - 唯一约束从“租户内名称唯一”升级为“租户 + 项目内名称唯一”，支持不同项目使用相同业务名称；
-- - 使用 information_schema + PREPARE 保持脚本幂等，便于重复执行和灰度升级。

SET @schema_name = DATABASE();

-- datasource_config：补租户、项目、工作空间字段。
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'datasource_config' AND column_name = 'tenant_id'),
    'SELECT 1',
    'ALTER TABLE datasource_config ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT ''租户 ID，用于多租户隔离、审计、配额和成本统计'' AFTER id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'datasource_config' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE datasource_config ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1 COMMENT ''项目 ID，用于 PROJECT 数据范围、项目级数据源列表和项目级权限边界'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'datasource_config' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE datasource_config ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，用于项目内研发/测试/生产等协作空间隔离'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- sync_template：补项目/空间字段。
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'sync_template' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE sync_template ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1 COMMENT ''项目 ID，用于租户内部项目级同步模板隔离、项目看板和项目审计'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'sync_template' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE sync_template ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，用于项目内协作空间筛选和后续空间级权限'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- sync_task：补项目/空间字段，并从模板继承历史归属。
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'sync_task' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE sync_task ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1 COMMENT ''项目 ID，通常继承自同步模板，用于项目级任务列表、调度看板和审计隔离'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'sync_task' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE sync_task ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，通常继承自同步模板，用于空间级运营台筛选'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE sync_task task
JOIN sync_template template ON template.id = task.template_id
SET task.project_id = template.project_id,
    task.workspace_id = template.workspace_id
WHERE task.project_id IS NULL OR task.project_id = 1;

-- 旧唯一索引会阻止不同项目使用同名数据源/模板/任务，需要替换为项目内唯一。
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'datasource_config' AND index_name = 'uk_datasource_name'),
    'ALTER TABLE datasource_config DROP INDEX uk_datasource_name',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'sync_template' AND index_name = 'uk_sync_template_tenant_name'),
    'ALTER TABLE sync_template DROP INDEX uk_sync_template_tenant_name',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'sync_task' AND index_name = 'uk_sync_task_tenant_name'),
    'ALTER TABLE sync_task DROP INDEX uk_sync_task_tenant_name',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'datasource_config' AND index_name = 'uk_datasource_tenant_project_name'),
    'SELECT 1',
    'ALTER TABLE datasource_config ADD UNIQUE KEY uk_datasource_tenant_project_name (tenant_id, project_id, name)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'sync_template' AND index_name = 'uk_sync_template_tenant_project_name'),
    'SELECT 1',
    'ALTER TABLE sync_template ADD UNIQUE KEY uk_sync_template_tenant_project_name (tenant_id, project_id, name)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'sync_task' AND index_name = 'uk_sync_task_tenant_project_name'),
    'SELECT 1',
    'ALTER TABLE sync_task ADD UNIQUE KEY uk_sync_task_tenant_project_name (tenant_id, project_id, name)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 查询索引：服务列表页、项目工作台、空间筛选和状态看板。
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'datasource_config' AND index_name = 'idx_datasource_tenant_project'),
    'SELECT 1',
    'ALTER TABLE datasource_config ADD INDEX idx_datasource_tenant_project (tenant_id, project_id, status)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'sync_template' AND index_name = 'idx_sync_template_project_mode'),
    'SELECT 1',
    'ALTER TABLE sync_template ADD INDEX idx_sync_template_project_mode (tenant_id, project_id, sync_mode, enabled)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'sync_task' AND index_name = 'idx_sync_task_project_state'),
    'SELECT 1',
    'ALTER TABLE sync_task ADD INDEX idx_sync_task_project_state (tenant_id, project_id, current_state)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
