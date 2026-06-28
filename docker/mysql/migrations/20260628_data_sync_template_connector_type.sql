-- data-sync：同步模板连接器类型低敏快照
--
-- 背景：
-- 1. data-sync 已有连接器能力矩阵，可以判断 MYSQL、POSTGRESQL、KAFKA、FILE、OBJECT_STORAGE 等连接器与同步模式是否兼容；
-- 2. 仅保存 source_datasource_id/target_datasource_id 时，模板校验需要每次跨服务回查 datasource-management 才能知道连接器类型；
-- 3. 在模板上冗余低敏 connector type，可以让模板校验、任务调度、运营筛选和 Agent 规划共享同一份能力事实。
--
-- 安全边界：
-- 1. 本迁移只新增 connector type，不保存连接串、host、port、database、topic、bucket、文件路径、账号、密钥、SQL 或样本数据；
-- 2. 该字段是低敏快照，后续仍应与 datasource-management 的真实实例能力探测做一致性校验；
-- 3. 旧模板允许为空，避免升级时阻断历史模板；新模板如果传任意一端 connector type，服务层会要求源/目标同时提供。

SET @schema_name = DATABASE();

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'source_connector_type'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN source_connector_type VARCHAR(64) COMMENT ''源端连接器类型低敏快照，例如 MYSQL、POSTGRESQL、KAFKA；不保存连接串、库名、topic、bucket、账号或密钥'' AFTER target_datasource_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND column_name = 'target_connector_type'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD COLUMN target_connector_type VARCHAR(64) COMMENT ''目标端连接器类型低敏快照，用于模板能力预检、任务调度和运营筛选'' AFTER source_connector_type'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = @schema_name AND table_name = 'data_sync_template' AND index_name = 'idx_data_sync_template_connector_mode'),
    'SELECT 1',
    'ALTER TABLE data_sync_template ADD INDEX idx_data_sync_template_connector_mode (tenant_id, source_connector_type, target_connector_type, sync_mode)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
