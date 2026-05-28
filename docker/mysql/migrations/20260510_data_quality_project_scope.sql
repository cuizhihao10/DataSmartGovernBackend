-- DataSmart Govern Backend
-- data-quality 项目/工作空间隔离迁移脚本
--
-- 设计说明：
-- 1. 质量规则、执行记录、报告和异常明细都需要具备 tenant/project/workspace 三层归属；
-- 2. 规则表保存归属源头，执行/报告/异常表冗余这些字段，避免质量大盘和异常工作台高频查询反复 join 规则表；
-- 3. 历史数据统一回填到 project_id=1，保证旧环境升级后仍可被默认项目继续访问；
-- 4. PROJECT 数据范围依赖 project_id 索引，否则 gateway 透传的授权项目集合无法低成本落到 SQL 查询。

SET @schema_name = DATABASE();

-- quality_rule：质量规则归属源头。
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_rule' AND column_name = 'tenant_id'),
    'SELECT 1',
    'ALTER TABLE quality_rule ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT ''租户 ID，用于质量规则租户隔离、租户级配额、审计导出和规则模板共享边界'' AFTER id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_rule' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE quality_rule ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1 COMMENT ''项目 ID，用于 PROJECT 数据范围、项目级质量规则列表、质量大盘和项目负责人权限边界'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_rule' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE quality_rule ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，用于项目内研发/测试/生产等协作空间隔离和空间级质量看板'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- execution/report/anomaly：运行事实表冗余归属字段，支撑项目级运营查询。
SET @tables = 'quality_check_execution,quality_check_report,quality_anomaly_detail';

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_check_execution' AND column_name = 'tenant_id'),
    'SELECT 1',
    'ALTER TABLE quality_check_execution ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT ''租户 ID，冗余自质量规则，用于租户级执行失败率、耗时和资源消耗统计'' AFTER id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_check_execution' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE quality_check_execution ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1 COMMENT ''项目 ID，冗余自质量规则，用于项目级执行历史、SLA、质量看板和排障隔离'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_check_execution' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE quality_check_execution ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，冗余自质量规则，用于空间级运行证据筛选'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_check_report' AND column_name = 'tenant_id'),
    'SELECT 1',
    'ALTER TABLE quality_check_report ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT ''租户 ID，冗余自质量规则，用于租户级质量评分、审计导出和保留周期清理'' AFTER id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_check_report' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE quality_check_report ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1 COMMENT ''项目 ID，冗余自质量规则，用于项目级报告检索、质量大盘、失败报告筛选和权限隔离'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_check_report' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE quality_check_report ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，冗余自质量规则，用于空间级质量趋势和生产/测试空间隔离'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_anomaly_detail' AND column_name = 'tenant_id'),
    'SELECT 1',
    'ALTER TABLE quality_anomaly_detail ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 1 COMMENT ''租户 ID，冗余自质量规则/报告，用于异常样本租户隔离、脱敏导出和保留策略'' AFTER id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_anomaly_detail' AND column_name = 'project_id'),
    'SELECT 1',
    'ALTER TABLE quality_anomaly_detail ADD COLUMN project_id BIGINT NOT NULL DEFAULT 1 COMMENT ''项目 ID，冗余自质量规则/报告，用于异常工作台、清洗任务创建、根因分析和 PROJECT 权限过滤'' AFTER tenant_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'quality_anomaly_detail' AND column_name = 'workspace_id'),
    'SELECT 1',
    'ALTER TABLE quality_anomaly_detail ADD COLUMN workspace_id BIGINT COMMENT ''工作空间 ID，冗余自质量规则/报告，用于空间级异常治理和运营告警隔离'' AFTER project_id'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史执行、报告、异常数据回填规则归属。
UPDATE quality_check_execution execution
JOIN quality_rule rule ON execution.rule_id = rule.id
SET execution.tenant_id = COALESCE(execution.tenant_id, rule.tenant_id),
    execution.project_id = COALESCE(execution.project_id, rule.project_id),
    execution.workspace_id = COALESCE(execution.workspace_id, rule.workspace_id);

UPDATE quality_check_report report
JOIN quality_rule rule ON report.rule_id = rule.id
SET report.tenant_id = COALESCE(report.tenant_id, rule.tenant_id),
    report.project_id = COALESCE(report.project_id, rule.project_id),
    report.workspace_id = COALESCE(report.workspace_id, rule.workspace_id);

UPDATE quality_anomaly_detail anomaly
JOIN quality_rule rule ON anomaly.rule_id = rule.id
SET anomaly.tenant_id = COALESCE(anomaly.tenant_id, rule.tenant_id),
    anomaly.project_id = COALESCE(anomaly.project_id, rule.project_id),
    anomaly.workspace_id = COALESCE(anomaly.workspace_id, rule.workspace_id);

-- 索引补充。旧的 name 唯一索引需要在真实环境中人工确认后替换；这里先补充新索引，避免破坏已有安装。
SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'quality_rule' AND index_name = 'uk_quality_rule_tenant_project_name'),
    'SELECT 1',
    'ALTER TABLE quality_rule ADD UNIQUE KEY uk_quality_rule_tenant_project_name (tenant_id, project_id, name)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'quality_check_report' AND index_name = 'idx_quality_report_project_status_time'),
    'SELECT 1',
    'ALTER TABLE quality_check_report ADD INDEX idx_quality_report_project_status_time (tenant_id, project_id, check_status, create_time)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'quality_anomaly_detail' AND index_name = 'idx_quality_anomaly_project_type_time'),
    'SELECT 1',
    'ALTER TABLE quality_anomaly_detail ADD INDEX idx_quality_anomaly_project_type_time (tenant_id, project_id, anomaly_type, create_time)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
