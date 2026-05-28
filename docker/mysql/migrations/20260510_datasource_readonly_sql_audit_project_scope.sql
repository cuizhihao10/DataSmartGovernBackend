-- DataSmart Govern Backend
-- datasource-management 受控只读 SQL 审计项目/空间快照迁移脚本
--
-- 设计说明：
-- 1. datasource_readonly_sql_execution_audit 记录的是“访问源库”这件事本身，属于合规证据；
-- 2. 历史审计不能只依赖 datasource_id 回表查询当前数据源归属，因为数据源未来可能迁移项目或改名；
-- 3. 因此审计表需要冗余数据源所属 tenant/project/workspace 快照；
-- 4. PROJECT 数据范围下的审计查询可以直接落到 datasource_project_id，不必 join datasource_config。

SET @schema_name = DATABASE();

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'datasource_readonly_sql_execution_audit' AND column_name = 'datasource_tenant_id'),
    'SELECT 1',
    'ALTER TABLE datasource_readonly_sql_execution_audit ADD COLUMN datasource_tenant_id BIGINT COMMENT ''被访问数据源所属租户 ID 快照；与 actor_tenant_id 分开保存，便于平台代操作和服务账号场景审计'' AFTER datasource_type'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'datasource_readonly_sql_execution_audit' AND column_name = 'datasource_project_id'),
    'SELECT 1',
    'ALTER TABLE datasource_readonly_sql_execution_audit ADD COLUMN datasource_project_id BIGINT COMMENT ''被访问数据源所属项目 ID 快照；用于 PROJECT 数据范围、项目级 SQL 访问审计报表和合规追溯'' AFTER datasource_tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'datasource_readonly_sql_execution_audit' AND column_name = 'datasource_workspace_id'),
    'SELECT 1',
    'ALTER TABLE datasource_readonly_sql_execution_audit ADD COLUMN datasource_workspace_id BIGINT COMMENT ''被访问数据源所属工作空间 ID 快照；用于研发/测试/生产空间级 SQL 访问审计筛选'' AFTER datasource_project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填历史审计。这里保留 COALESCE，是为了避免重复执行迁移时覆盖已经被人工修正过的审计快照。
UPDATE datasource_readonly_sql_execution_audit audit
JOIN datasource_config datasource ON audit.datasource_id = datasource.id
SET audit.datasource_tenant_id = COALESCE(audit.datasource_tenant_id, datasource.tenant_id),
    audit.datasource_project_id = COALESCE(audit.datasource_project_id, datasource.project_id),
    audit.datasource_workspace_id = COALESCE(audit.datasource_workspace_id, datasource.workspace_id);

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'datasource_readonly_sql_execution_audit' AND index_name = 'idx_datasource_readonly_audit_project_time'),
    'SELECT 1',
    'ALTER TABLE datasource_readonly_sql_execution_audit ADD INDEX idx_datasource_readonly_audit_project_time (datasource_tenant_id, datasource_project_id, executed_at)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'datasource_readonly_sql_execution_audit' AND index_name = 'idx_datasource_readonly_audit_workspace_time'),
    'SELECT 1',
    'ALTER TABLE datasource_readonly_sql_execution_audit ADD INDEX idx_datasource_readonly_audit_workspace_time (datasource_tenant_id, datasource_workspace_id, executed_at)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
